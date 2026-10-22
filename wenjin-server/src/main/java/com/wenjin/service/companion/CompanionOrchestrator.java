package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.ai.SinkFailureException;
import com.wenjin.service.CompanionService.CompanionStreamSink;
import com.wenjin.service.trace.StepRecord;
import com.wenjin.service.trace.TraceContext;
import com.wenjin.support.AgentRunStatus;
import com.wenjin.support.AgentStepStatus;
import com.wenjin.support.AgentStepType;
import com.wenjin.support.CompanionFinalTextSanitizer;
import com.wenjin.support.StreamingToolProtocolFilter;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.dto.CodeResultVO;
import com.wenjin.dto.ProbeStartVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 伴侣工具编排单循环（真流式）。
 *
 * <p>每轮一次 {@link CompanionAiClient#streamTurn}，始终携带工具 schema：仍有轮次与预算时 {@code AUTO}，
 * 最后一轮 / 预算耗尽 / 失败重试时 {@code NONE}。内容增量经 {@link StreamingToolProtocolFilter}
 * 与输出上限守卫后立即下发；有工具调用则执行（同轮全只读且预算足够时并行）后继续下一轮。
 *
 * <p>轮次：{@code maxRounds} 为可调用工具的轮数，其后至多再有一轮 {@code NONE} 总结（与旧
 * phase-1 × maxRounds + phase-2 的调用上限一致）。
 *
 * <p>会话 / 持久化 / 降级 / legacy 回退 / trace 落库由外壳通过 {@link Shell} 提供；本类无状态，
 * 每个 turn new 一个即可。
 */
public final class CompanionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CompanionOrchestrator.class);

    public static final String STEP_LLM_ROUND = "llm_round";
    public static final String ERROR_CLIENT_DISCONNECTED = "CLIENT_DISCONNECTED";
    public static final String ERROR_LLM_INTERRUPTED = "LLM_INTERRUPTED";
    public static final String ERROR_LLM = "LLM_ERROR";

    /** turnSignals 键：本 turn {@code get_recent_mistakes} 结果中的第一误区节点码（误区定向探针用）。 */
    public static final String SIGNAL_MISCONCEPTION_NODE = "misconceptionNodeCode";

    private static final ObjectMapper SIGNAL_MAPPER = new ObjectMapper();

    static final String FALLBACK_AFTER_PRACTICE =
            "练习已生成，可以直接在上方卡片里作答。有问题随时问我。";
    static final String FALLBACK_AFTER_TOOLS =
            "我已根据工具结果整理完毕。你还想继续了解哪一块？";
    static final String INTERRUPTED_NOTICE = "\n\n[回答中断：模型服务异常，请稍后重试]";

    /** 外壳回调：由 CompanionServiceImpl 提供。 */
    public interface Shell {
        /** 零 LLM 降级（下发 + 持久化由外壳负责），返回 run 状态。 */
        int degrade(CompanionStreamSink sink, String reason);

        /** 首轮失败且尚无工具上下文时回退 legacy 流式路径（下发 + 持久化 + trace 由外壳负责）。 */
        void streamLegacy(CompanionStreamSink sink);

        /** 失败重试用的无工具说明系统提示。 */
        String fallbackSystemPrompt();

        void persist(String text);

        boolean cancelRequested();

        void step(StepRecord record);
    }

    /**
     * @param forcedCall 意图路由合成的工具调用（如 start_practice）；非空时先执行它，再一轮 NONE 总结
     * @param injectedChunkIds 本 turn RETRIEVAL 槽注入的 chunkId；<b>null = 引用校验关闭</b>（rag-enabled=false），
     *                         非 null（含空集）= 开启，可引用集合 = 此集合 ∪ search_materials 返回的 chunkId
     */
    public record Request(List<Map<String, Object>> messages,
                          List<CompanionTool> tools,
                          ToolContext toolContext,
                          CompanionAiClient.ToolCall forcedCall,
                          int maxRounds,
                          int maxToolCalls,
                          int maxOutputChars,
                          String truncationNotice,
                          TraceContext traceCtx,
                          Collection<Long> injectedChunkIds) {
    }

    /**
     * @param toolsCalled 本 turn 实际执行（含被拦截）的工具名，按执行顺序
     * @param turnSignals 工具结构化结果中提取、供外壳后处理用的少量信号（如 {@link #SIGNAL_MISCONCEPTION_NODE}）
     */
    public record Outcome(int runStatus, String errorCode, List<String> toolsCalled, Map<String, String> turnSignals) {
        public Outcome {
            toolsCalled = toolsCalled == null ? List.of() : List.copyOf(toolsCalled);
            turnSignals = turnSignals == null ? Map.of() : Map.copyOf(turnSignals);
        }

        public Outcome(int runStatus, String errorCode, List<String> toolsCalled) {
            this(runStatus, errorCode, toolsCalled, Map.of());
        }
    }

    /** 降级原因（与 CompanionDegradeService 常量一致，避免本类依赖其 Spring 装配）。 */
    public static final String REASON_LLM_TIMEOUT = CompanionDegradeService.REASON_LLM_TIMEOUT;
    public static final String REASON_LLM_FAILURES = CompanionDegradeService.REASON_LLM_FAILURES;

    private final CompanionAiClient aiClient;
    private final ToolGovernor toolGovernor;
    private final Shell shell;

    public CompanionOrchestrator(CompanionAiClient aiClient, ToolGovernor toolGovernor, Shell shell) {
        this.aiClient = Objects.requireNonNull(aiClient, "aiClient");
        this.toolGovernor = Objects.requireNonNull(toolGovernor, "toolGovernor");
        this.shell = Objects.requireNonNull(shell, "shell");
    }

    public Outcome run(Request req, CompanionStreamSink rawSink) {
        Turn turn = new Turn(req, new GuardedSink(rawSink));
        try {
            return turn.execute();
        } catch (SinkFailureException e) {
            return turn.disconnected(e);
        } catch (RuntimeException e) {
            if (turn.out.disconnected()) {
                return turn.disconnected(e);
            }
            throw e;
        }
    }

    // ── 单个 turn 的可变状态 ────────────────────────────────────────────────

    private final class Turn {
        private final Request req;
        private final GuardedSink out;
        private final TextAccumulator text;
        private final ToolExecutionState state;
        private final List<Map<String, Object>> schemas;
        private final Map<String, String> labels;
        private final List<String> toolsCalled = new ArrayList<>();
        private final Map<String, String> signals = new LinkedHashMap<>();
        private List<Map<String, Object>> messages;
        private boolean practiceStarted;
        /** 引用守卫；null = 校验关闭（rag-enabled=false）。 */
        private final CitationGuard citations;

        Turn(Request req, GuardedSink out) {
            this.req = req;
            this.citations = req.injectedChunkIds() == null ? null : new CitationGuard(req.injectedChunkIds());
            this.out = out;
            this.text = new TextAccumulator(out, Math.max(0, req.maxOutputChars()), req.truncationNotice());
            this.state = new ToolExecutionState(req.maxToolCalls());
            List<CompanionTool> tools = req.tools() == null ? List.of() : req.tools();
            this.schemas = tools.stream().map(CompanionTool::schema).toList();
            this.labels = buildToolLabels(tools);
            this.messages = new ArrayList<>(req.messages());
        }

        Outcome execute() {
            boolean forceFinal = false;
            if (req.forcedCall() != null) {
                messages.add(assistantToolCallMessage("", List.of(req.forcedCall())));
                if (!executeTools(List.of(req.forcedCall()))) {
                    return cancelled();
                }
                forceFinal = true;
            }

            int llmFailures = 0;
            int round = 0;
            while (true) {
                if (shell.cancelRequested()) {
                    return cancelled();
                }
                boolean finalRound = forceFinal || round >= req.maxRounds() || !budgetLeft();
                CompanionAiClient.ToolChoice choice = finalRound
                        ? CompanionAiClient.ToolChoice.NONE
                        : CompanionAiClient.ToolChoice.AUTO;
                int thisRound = round++;
                String digest = messagesDigest(messages);
                long started = System.currentTimeMillis();
                text.beginRound();
                CompanionAiClient.StreamTurnResult result;
                try {
                    result = aiClient.streamTurn(messages, schemas, choice, text::accept);
                    text.endRound();
                } catch (RuntimeException ex) {
                    if (ex instanceof SinkFailureException || out.disconnected()) {
                        text.abortRound();
                        recordRound(thisRound, digest, null, text.roundChars(), started,
                                AgentStepStatus.FAILED, ERROR_CLIENT_DISCONNECTED);
                        throw ex instanceof SinkFailureException sfe ? sfe : new SinkFailureException(ex);
                    }
                    text.abortRound();
                    recordRound(thisRound, digest, null, text.roundChars(), started,
                            AgentStepStatus.FAILED, ERROR_LLM);
                    llmFailures++;
                    log.warn("Companion LLM round {} failed (failures={})", thisRound, llmFailures, ex);
                    if (text.emittedVisible()) {
                        return interrupted();
                    }
                    if (llmFailures >= 2 || isTimeout(ex)) {
                        return degraded(ex);
                    }
                    if (hasToolContext(messages)) {
                        messages = withSystemPrompt(messages, shell.fallbackSystemPrompt());
                        forceFinal = true;
                        continue;
                    }
                    return legacyFallback();
                }
                recordRound(thisRound, digest, result, contentLength(result), started, AgentStepStatus.OK, null);

                List<CompanionAiClient.ToolCall> calls = result.toolCalls() == null
                        ? List.of()
                        : result.toolCalls().stream().filter(Objects::nonNull).toList();
                if (calls.isEmpty() || finalRound || text.capped()) {
                    break;
                }
                messages.add(assistantToolCallMessage(text.lastRoundSanitized(), calls, result.reasoningContent()));
                if (!executeTools(calls)) {
                    return cancelled();
                }
            }
            return finish();
        }

        private boolean budgetLeft() {
            return state.used() < state.maxToolCalls();
        }

        private Outcome finish() {
            String persisted = text.persistable();
            boolean emitFallback = false;
            if (persisted.isEmpty()) {
                persisted = practiceStarted ? FALLBACK_AFTER_PRACTICE : FALLBACK_AFTER_TOOLS;
                emitFallback = !text.emittedVisible();
            }
            // 引用校验：无效 `资料 #N` 在持久化文本中替换为「（引用无效）」；流式已下发的原文由 citations 事件通知前端划掉
            CitationGuard.Verdict verdict = null;
            if (citations != null) {
                long checkStarted = System.currentTimeMillis();
                verdict = citations.check(persisted);
                persisted = verdict.text();
                if (verdict.hasCitations()) {
                    step(citations.toStep(verdict, elapsedMs(checkStarted)));
                }
            }
            // 先落库再推送：推送失败（断开）走 run() 的 disconnected，其 persistable() 为空不会重复落库
            shell.persist(persisted);
            if (emitFallback) {
                out.token(persisted);
            }
            if (verdict != null && verdict.hasCitations()) {
                out.citations(verdict.valid(), verdict.invalid());
            }
            return new Outcome(AgentRunStatus.SUCCEEDED, null, toolsCalled, signals);
        }

        private Outcome cancelled() {
            persistIfAny(text.persistable());
            return new Outcome(AgentRunStatus.CANCELLED, null, toolsCalled, signals);
        }

        Outcome disconnected(RuntimeException cause) {
            log.info("Companion client disconnected; stopping turn: {}", cause.toString());
            persistIfAny(text.persistable());
            return new Outcome(AgentRunStatus.CANCELLED, ERROR_CLIENT_DISCONNECTED, toolsCalled, signals);
        }

        private Outcome interrupted() {
            try {
                out.token(INTERRUPTED_NOTICE);
            } catch (SinkFailureException ignored) {
                // 客户端也已断开：仍按 LLM 中断记 FAILED 并持久化已生成文本
            }
            String generated = text.persistable();
            shell.persist(neutralizeCitations(generated) + INTERRUPTED_NOTICE);
            return new Outcome(AgentRunStatus.FAILED, ERROR_LLM_INTERRUPTED, toolsCalled, signals);
        }

        private Outcome degraded(Throwable ex) {
            int status = shell.degrade(out, isTimeout(ex) ? REASON_LLM_TIMEOUT : REASON_LLM_FAILURES);
            return new Outcome(status, null, toolsCalled, signals);
        }

        private Outcome legacyFallback() {
            try {
                shell.streamLegacy(out);
                return new Outcome(AgentRunStatus.SUCCEEDED, null, toolsCalled, signals);
            } catch (RuntimeException legacyEx) {
                if (legacyEx instanceof SinkFailureException || out.disconnected()) {
                    throw legacyEx;
                }
                log.warn("Companion legacy fallback failed", legacyEx);
                // 第二次 LLM 失败 → 降级
                return degraded(legacyEx);
            }
        }

        private void persistIfAny(String content) {
            if (content != null && !content.isEmpty()) {
                shell.persist(neutralizeCitations(content));
            }
        }

        /** 中断 / 取消 / 断开路径同样不留无效引用（仅改写，不发事件、不记 step）。 */
        private String neutralizeCitations(String content) {
            return citations == null ? content : citations.neutralize(content);
        }

        /** @return false 表示中途被取消 */
        private boolean executeTools(List<CompanionAiClient.ToolCall> calls) {
            if (canRunInParallel(calls)) {
                return executeParallel(calls);
            }
            for (CompanionAiClient.ToolCall call : calls) {
                if (shell.cancelRequested()) {
                    return false;
                }
                String name = toolName(call);
                out.toolStart(name, labels.getOrDefault(name, name));
                long started = System.currentTimeMillis();
                ToolExecutionResult result = toolGovernor.execute(req.toolContext(), call, state, req.traceCtx());
                applyResult(call, result, elapsedMs(started));
            }
            return true;
        }

        /** 同轮全只读、多于 1 个，且剩余只读预算足够（预算判定与顺序执行一致、结果确定）。 */
        private boolean canRunInParallel(List<CompanionAiClient.ToolCall> calls) {
            if (calls.size() < 2) {
                return false;
            }
            for (CompanionAiClient.ToolCall call : calls) {
                if (!ToolGovernor.READ_CACHEABLE_TOOLS.contains(toolName(call))) {
                    return false;
                }
            }
            return calls.size() <= state.nonSideCap() - state.nonSideUsed();
        }

        private boolean executeParallel(List<CompanionAiClient.ToolCall> calls) {
            if (shell.cancelRequested()) {
                return false;
            }
            for (CompanionAiClient.ToolCall call : calls) {
                String name = toolName(call);
                out.toolStart(name, labels.getOrDefault(name, name));
            }
            List<Future<TimedResult>> futures = new ArrayList<>(calls.size());
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (CompanionAiClient.ToolCall call : calls) {
                    futures.add(executor.submit(() -> {
                        long started = System.currentTimeMillis();
                        ToolExecutionResult r = toolGovernor.execute(req.toolContext(), call, state, req.traceCtx());
                        return new TimedResult(r, elapsedMs(started));
                    }));
                }
            }
            List<TimedResult> results = new ArrayList<>(calls.size());
            for (Future<TimedResult> future : futures) {
                results.add(await(future));
            }
            // 批次执行期间学生已取消：不再回放结果（与顺序执行"每个工具前检查"的语义对齐）
            if (shell.cancelRequested()) {
                return false;
            }
            for (int i = 0; i < calls.size(); i++) {
                TimedResult timed = results.get(i);
                applyResult(calls.get(i), timed.result(), timed.elapsedMs());
            }
            return true;
        }

        private void applyResult(CompanionAiClient.ToolCall call, ToolExecutionResult result, int elapsedMs) {
            String name = toolName(call);
            toolsCalled.add(name);
            recordToolStep(call, result, elapsedMs);
            messages.add(toolMessage(call, result));
            boolean ok = result != null && result.ok();
            if (citations != null && ok) {
                citations.addToolResult(name, result.content());
            }
            if (ok && GetRecentMistakesTool.NAME.equals(name)) {
                String code = topMisconceptionNode(result.content());
                if (code != null) {
                    signals.putIfAbsent(SIGNAL_MISCONCEPTION_NODE, code);
                }
            }
            out.toolResult(name, ok);
            if (result != null && result.practiceCard() != null) {
                out.practiceCard(result.practiceCard());
                practiceStarted = true;
            } else if (ok && "start_practice".equals(name)) {
                practiceStarted = true;
            }
            if (result != null && result.codeCard() != null) {
                out.codeResult(result.codeCard());
            }
        }

        // ── trace ──

        private void recordRound(int round,
                                 String digest,
                                 CompanionAiClient.StreamTurnResult result,
                                 int contentChars,
                                 long started,
                                 int status,
                                 String errorCode) {
            String output = roundOutputJson(round, result, contentChars);
            int tokensIn;
            int tokensOut;
            CompanionAiClient.Usage usage = result == null ? null : result.usage();
            if (usage != null) {
                tokensIn = Math.max(0, usage.promptTokens());
                tokensOut = Math.max(0, usage.completionTokens());
            } else {
                tokensIn = estimateTokens(digest);
                tokensOut = result == null || result.content() == null ? contentChars / 2
                        : estimateTokens(result.content());
                TraceContext ctx = req.traceCtx();
                if (ctx != null && !ctx.isNoop() && (tokensIn > 0 || tokensOut > 0)) {
                    ctx.markTokensEstimated();
                }
            }
            step(new StepRecord(AgentStepType.LLM_CALL, STEP_LLM_ROUND, status, digest, output,
                    tokensIn, tokensOut, elapsedMs(started), errorCode));
        }

        private void recordToolStep(CompanionAiClient.ToolCall call, ToolExecutionResult result, int elapsedMs) {
            String name = call == null || call.name() == null || call.name().isBlank() ? "unknown" : call.name();
            int status;
            if (result != null && result.cached()) {
                status = AgentStepStatus.CACHED;
            } else if (result != null && result.ok()) {
                status = AgentStepStatus.OK;
            } else {
                status = AgentStepStatus.FAILED;
            }
            String errorCode = status == AgentStepStatus.FAILED ? extractToolErrorCode(result) : null;
            // Governor intercepts (budget/whitelist/args/timeout) → GUARD; actual execution → TOOL_CALL
            String stepType = result != null && result.guarded() ? AgentStepType.GUARD : AgentStepType.TOOL_CALL;
            step(new StepRecord(stepType, name, status,
                    call == null ? null : call.argumentsJson(),
                    result == null ? null : result.content(),
                    0, 0, elapsedMs, errorCode));
        }

        private void step(StepRecord record) {
            try {
                shell.step(record);
            } catch (RuntimeException e) {
                log.warn("agentTrace step failed: {}", e.toString());
            }
        }
    }

    private record TimedResult(ToolExecutionResult result, int elapsedMs) {
    }

    /** {@code get_recent_mistakes} 结果 → {@code topMisconceptions[0].nodeCode}；无 / 解析失败 → null。 */
    static String topMisconceptionNode(String toolContent) {
        if (toolContent == null || toolContent.isBlank()) {
            return null;
        }
        try {
            JsonNode code = SIGNAL_MAPPER.readTree(toolContent).path("topMisconceptions").path(0).path("nodeCode");
            return code.isTextual() && !code.asText().isBlank() ? code.asText().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static TimedResult await(Future<TimedResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tool execution interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause);
        }
    }

    // ── 文本累积：过滤 → 上限守卫 → 下发；分轮记录供持久化 ───────────────────────

    private static final class TextAccumulator {
        private final GuardedSink out;
        private final int maxChars;
        private final String truncationNotice;
        private final List<String> segments = new ArrayList<>();
        private StreamingToolProtocolFilter filter;
        private StringBuilder round = new StringBuilder();
        private int forwarded;
        private boolean capped;
        private boolean emittedVisible;

        TextAccumulator(GuardedSink out, int maxChars, String truncationNotice) {
            this.out = out;
            this.maxChars = maxChars;
            this.truncationNotice = truncationNotice == null ? "" : truncationNotice;
        }

        void beginRound() {
            filter = new StreamingToolProtocolFilter();
            round = new StringBuilder();
        }

        void accept(String delta) {
            if (capped || delta == null || delta.isEmpty()) {
                return;
            }
            forward(filter.accept(delta));
        }

        void endRound() {
            if (!capped) {
                forward(filter.flush());
            }
            closeRound();
        }

        /** 轮次失败：未决的暂扣文本丢弃，已下发部分仍记入。 */
        void abortRound() {
            closeRound();
        }

        private void closeRound() {
            if (round.length() > 0) {
                segments.add(round.toString());
            }
        }

        int roundChars() {
            return round.length();
        }

        String lastRoundSanitized() {
            return CompanionFinalTextSanitizer.sanitize(round.toString());
        }

        boolean capped() {
            return capped;
        }

        boolean emittedVisible() {
            return emittedVisible;
        }

        private void forward(String piece) {
            if (piece == null || piece.isEmpty() || capped) {
                return;
            }
            int room = maxChars - forwarded;
            if (piece.length() < room) {
                emit(piece);
                return;
            }
            emit(piece.substring(0, Math.max(0, room)));
            capped = true;
            out.token(truncationNotice);
        }

        private void emit(String piece) {
            if (piece.isEmpty()) {
                return;
            }
            round.append(piece);
            forwarded += piece.length();
            if (!piece.isBlank()) {
                emittedVisible = true;
            }
            out.token(piece);
        }

        /** 各轮文本以空行拼接后整段净化；触顶时补截断提示。 */
        String persistable() {
            String clean = CompanionFinalTextSanitizer.sanitize(String.join("\n\n", segments));
            if (clean.isEmpty()) {
                return clean;
            }
            if (capped && !clean.contains("回复已截断")) {
                clean = clean + truncationNotice;
            }
            return clean;
        }
    }

    // ── sink 守卫：任一回调失败即视为客户端断开，此后不再触达下游 ─────────────────

    private static final class GuardedSink extends CompanionStreamSink {
        private final CompanionStreamSink delegate;
        private volatile boolean disconnected;

        GuardedSink(CompanionStreamSink delegate) {
            this.delegate = Objects.requireNonNull(delegate, "sink");
        }

        boolean disconnected() {
            return disconnected;
        }

        private void call(Runnable action) {
            if (disconnected) {
                throw new SinkFailureException("client already disconnected", null);
            }
            try {
                action.run();
            } catch (RuntimeException e) {
                disconnected = true;
                throw e instanceof SinkFailureException sfe ? sfe : new SinkFailureException(e);
            }
        }

        @Override
        public void token(String token) {
            if (token == null || token.isEmpty()) {
                return;
            }
            call(() -> delegate.token(token));
        }

        @Override
        public void toolStart(String name, String label) {
            call(() -> delegate.toolStart(name, label));
        }

        @Override
        public void toolResult(String name, boolean ok) {
            call(() -> delegate.toolResult(name, ok));
        }

        @Override
        public void practiceCard(PracticeStartVO card) {
            call(() -> delegate.practiceCard(card));
        }

        @Override
        public void probeCard(ProbeStartVO card) {
            call(() -> delegate.probeCard(card));
        }

        @Override
        public void codeResult(CodeResultVO card) {
            call(() -> delegate.codeResult(card));
        }

        @Override
        public void citations(List<Long> valid, List<Long> invalid) {
            call(() -> delegate.citations(valid, invalid));
        }
    }

    // ── 消息与工具辅助 ─────────────────────────────────────────────────────

    private static String toolName(CompanionAiClient.ToolCall call) {
        return call == null || call.name() == null ? "" : call.name();
    }

    private static Map<String, String> buildToolLabels(List<CompanionTool> tools) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (CompanionTool tool : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            labels.put(tool.name(), tool.label());
        }
        return labels;
    }

    static Map<String, Object> assistantToolCallMessage(String content, List<CompanionAiClient.ToolCall> calls) {
        return assistantToolCallMessage(content, calls, null);
    }

    /**
     * @param reasoningContent thinking-mode reasoning of this round; DeepSeek requires it to be passed
     *                         back within the same turn's follow-up rounds. Added only when non-empty,
     *                         so non-thinking providers never see the field.
     */
    public static Map<String, Object> assistantToolCallMessage(String content,
                                                               List<CompanionAiClient.ToolCall> calls,
                                                               String reasoningContent) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content == null ? "" : content);
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            message.put("reasoning_content", reasoningContent);
        }
        message.put("tool_calls", calls.stream().map(CompanionOrchestrator::toolCallPayload).toList());
        return message;
    }

    private static Map<String, Object> toolCallPayload(CompanionAiClient.ToolCall call) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", call.name());
        function.put("arguments", call.argumentsJson() == null ? "{}" : call.argumentsJson());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", call.id() == null ? "" : call.id());
        item.put("type", "function");
        item.put("function", function);
        return item;
    }

    private static Map<String, Object> toolMessage(CompanionAiClient.ToolCall call, ToolExecutionResult result) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        String id = result != null && result.toolCallId() != null ? result.toolCallId()
                : call.id() == null ? "" : call.id();
        message.put("tool_call_id", id);
        message.put("content", result == null ? "" : result.content());
        return message;
    }

    private static boolean hasToolContext(List<Map<String, Object>> messages) {
        return messages.stream().anyMatch(message -> Objects.equals(message.get("role"), "tool"));
    }

    private static List<Map<String, Object>> withSystemPrompt(List<Map<String, Object>> messages, String prompt) {
        List<Map<String, Object>> copy = new ArrayList<>(messages);
        if (!copy.isEmpty() && prompt != null) {
            Map<String, Object> system = new LinkedHashMap<>();
            system.put("role", "system");
            system.put("content", prompt);
            copy.set(0, system);
        }
        return copy;
    }

    /** Prefer envelope {@code code}; fall back to legacy TOOL_FAILED. */
    private static String extractToolErrorCode(ToolExecutionResult result) {
        if (result == null || result.content() == null || result.content().isBlank()) {
            return "TOOL_FAILED";
        }
        String content = result.content();
        int codeIdx = content.indexOf("\"code\"");
        if (codeIdx < 0) {
            return "TOOL_FAILED";
        }
        int colon = content.indexOf(':', codeIdx);
        int firstQuote = content.indexOf('"', colon + 1);
        int secondQuote = firstQuote < 0 ? -1 : content.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return "TOOL_FAILED";
        }
        String code = content.substring(firstQuote + 1, secondQuote);
        return code.isBlank() ? "TOOL_FAILED" : code;
    }

    private static int contentLength(CompanionAiClient.StreamTurnResult result) {
        return result == null || result.content() == null ? 0 : result.content().length();
    }

    /** {@code {"round":n,"toolCalls":[names],"contentChars":k,"finishReason":"..."|null}} */
    static String roundOutputJson(int round, CompanionAiClient.StreamTurnResult result, int contentChars) {
        StringBuilder names = new StringBuilder();
        if (result != null && result.toolCalls() != null) {
            boolean first = true;
            for (CompanionAiClient.ToolCall call : result.toolCalls()) {
                if (call == null) {
                    continue;
                }
                if (!first) {
                    names.append(',');
                }
                first = false;
                names.append('"').append(escapeJson(call.name())).append('"');
            }
        }
        String finish = result == null || result.finishReason() == null
                ? "null"
                : "\"" + escapeJson(result.finishReason()) + "\"";
        return "{\"round\":" + round
                + ",\"toolCalls\":[" + names + "]"
                + ",\"contentChars\":" + contentChars
                + ",\"finishReason\":" + finish + "}";
    }

    public static String messagesDigest(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("msgs=").append(messages.size());
        for (Map<String, Object> message : messages) {
            if (message == null) {
                continue;
            }
            Object content = message.get("content");
            String c = content == null ? "" : String.valueOf(content);
            sb.append('|').append(message.get("role")).append(':').append(truncate(c, 80));
            if (message.containsKey("tool_calls")) {
                sb.append("+tool_calls");
            }
            if (sb.length() > 500) {
                sb.append("…");
                break;
            }
        }
        return sb.toString();
    }

    /** 无上游 usage 时按 chars/2 估算 token。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / 2;
    }

    public static boolean isTimeout(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            String name = t.getClass().getName();
            String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
            if (name.contains("Timeout") || msg.contains("timeout") || msg.contains("timed out")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    public static String escapeJson(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String truncate(String value, int max) {
        if (value == null || max <= 0 || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }

    private static int elapsedMs(long startedMs) {
        long d = System.currentTimeMillis() - startedMs;
        return d < 0 ? 0 : (int) Math.min(d, Integer.MAX_VALUE);
    }
}
