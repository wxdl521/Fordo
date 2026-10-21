package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AiProperties;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.service.memory.MemoryJobEnqueueService;
import com.wenjin.service.memory.StudentMemoryService;
import com.wenjin.service.rag.RetrievalService;
import com.wenjin.dto.CompanionChatRequest;
import com.wenjin.dto.CompanionConversationVO;
import com.wenjin.dto.CompanionMessageVO;
import com.wenjin.dto.DiagnosticResultVO;
import com.wenjin.dto.LearningPathVO;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.entity.CompanionMessage;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.CompanionConversationMapper;
import com.wenjin.mapper.CompanionMessageMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.companion.CompanionDegradeService;
import com.wenjin.service.companion.CompanionOrchestrator;
import com.wenjin.service.companion.CompanionTool;
import com.wenjin.service.companion.ReflectionCoordinator;
import com.wenjin.service.companion.StrategySwitcher;
import com.wenjin.service.companion.ToolContext;
import com.wenjin.service.companion.ToolGovernor;
import com.wenjin.service.plan.PlanCancelGuard;
import com.wenjin.service.trace.AgentTraceService;
import com.wenjin.service.trace.StartRunCmd;
import com.wenjin.service.trace.StepRecord;
import com.wenjin.service.trace.TraceContext;
import com.wenjin.support.AgentRunStatus;
import com.wenjin.support.AgentStepStatus;
import com.wenjin.support.AgentStepType;
import com.wenjin.support.CompanionIntent;
import com.wenjin.support.CompanionIntentClassifier;
import com.wenjin.support.ConversationContextBuilder;
import com.wenjin.support.Phase1Decision;
import com.wenjin.support.PracticeIntent;
import com.wenjin.support.PracticeIntentDetector;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompanionServiceImpl implements CompanionService {

    private static final Logger log = LoggerFactory.getLogger(CompanionServiceImpl.class);
    private static final int ROLE_USER = 1;
    private static final int ROLE_AI = 2;

    private final CompanionConversationMapper conversationMapper;
    private final CompanionMessageMapper messageMapper;
    private final KgNodeMapper nodeMapper;
    private final StudentMasteryMapper masteryMapper;
    private final CourseMapper courseMapper;
    private final PathService pathService;
    private final DiagnosticResultService diagnosticResultService;
    private final CompanionAiClient aiClient;
    private final MasteryDecayCalculator decayCalculator;
    private final CourseService courseService;
    private final ConversationContextBuilder contextBuilder;
    private final LimitsProperties limits;

    @Autowired(required = false)
    private ToolGovernor toolGovernor;

    @Autowired(required = false)
    private List<CompanionTool> companionTools = List.of();

    @Autowired(required = false)
    private CompanionToolProperties toolProperties = disabledToolProperties();

    /** Agent 轨迹埋点；缺省 / 关闭时走 NOOP，失败不得影响主流程。 */
    @Autowired(required = false)
    private AgentTraceService agentTraceService;

    /** 当前生效的 AI 配置；agent_run.model 取其调用时的 model（系统配置可热改，故不缓存启动值）。 */
    @Autowired(required = false)
    private AiProperties aiProperties;

    /** 摘要推进后派发 MEMORY_DISTILL / SUMMARY_REFRESH；缺省时跳过。 */
    @Autowired(required = false)
    private MemoryJobEnqueueService memoryJobEnqueueService;

    /** 长期记忆注入 / 工具；缺省或 feature 关闭时 MEMORY 槽为空。 */
    @Autowired(required = false)
    private StudentMemoryService studentMemoryService;

    /** RAG 检索注入；缺省或 rag-enabled=false 时 RETRIEVAL 槽为空。 */
    @Autowired(required = false)
    private RetrievalService retrievalService;

    @Autowired(required = false)
    private FeatureFlagsProperties featureFlags;

    /** M9 零 LLM 降级。 */
    @Autowired(required = false)
    private CompanionDegradeService degradeService;

    /** M9 反思探针协调。 */
    @Autowired(required = false)
    private ReflectionCoordinator reflectionCoordinator;

    /** M10 run 级取消检查（step 边界）。 */
    @Autowired(required = false)
    private PlanCancelGuard planCancelGuard;

    @Override
    @Transactional
    public Long startTurn(CompanionChatRequest req) {
        if (req.getStudentId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "studentId 不能为空");
        }
        if (req.getCourseId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "courseId 不能为空");
        }
        if (req.getMessage() == null || req.getMessage().trim().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息内容不能为空");
        }
        int maxInput = limits.getCompanion().getMaxInputChars();
        if (req.getMessage().length() > maxInput) {
            throw new BusinessException(ResultCode.PAYLOAD_TOO_LARGE,
                    "消息长度超限：" + req.getMessage().length() + " > " + maxInput);
        }

        Long conversationId = req.getConversationId();
        if (conversationId == null) {
            CompanionConversation conversation = new CompanionConversation();
            conversation.setStudentId(req.getStudentId());
            conversation.setCourseId(req.getCourseId());
            conversation.setNodeCode(req.getNodeCode());
            conversation.setTitle(buildTitle(req));
            conversation.setCreatedAt(LocalDateTime.now());
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.insert(conversation);
            conversationId = conversation.getId();
        } else {
            CompanionConversation conversation = conversationMapper.selectById(conversationId);
            if (conversation == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在: " + conversationId);
            }
            if (!Objects.equals(conversation.getStudentId(), req.getStudentId())
                    || !Objects.equals(conversation.getCourseId(), req.getCourseId())) {
                throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该会话");
            }
            if (req.getNodeCode() != null && !req.getNodeCode().isBlank()) {
                conversation.setNodeCode(req.getNodeCode());
            }
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
        }

        CompanionMessage userMessage = new CompanionMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(req.getMessage());
        userMessage.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(userMessage);
        return conversationId;
    }

    @Override
    public void streamReply(Long conversationId, Consumer<String> onToken) {
        streamReply(conversationId, new CompanionStreamSink() {
            @Override
            public void token(String token) {
                onToken.accept(token);
            }
        });
    }

    @Override
    public void streamReply(Long conversationId, CompanionStreamSink sink) {
        streamReply(conversationId, null, sink);
    }

    @Override
    public void streamReply(Long conversationId, Long reviewPracticeSessionId, CompanionStreamSink sink) {
        CompanionConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在: " + conversationId);
        }

        // P0-2：仅加载 watermark 之后的消息（已折叠进摘要的 id 不再全量拉取）
        List<CompanionMessage> history = messageMapper.selectSinceIdAsc(
                conversationId, conversation.getSummaryThroughMessageId());
        if (history == null) {
            // 仅 null 回退（测试 stub 未 mock 时）；空列表表示真实无增量消息，不得全量重载
            history = messageMapper.selectList(
                    new LambdaQueryWrapper<CompanionMessage>()
                            .eq(CompanionMessage::getConversationId, conversationId)
                            .orderByAsc(CompanionMessage::getId));
        }
        if (history == null || history.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "会话无历史消息");
        }

        ConversationContextBuilder.BuiltContext ctx = contextBuilder.build(conversation, history);
        if (ctx.summaryUpdated()) {
            int rows = conversationMapper.updateSummaryGuarded(
                    conversationId, ctx.summaryText(), ctx.summaryThroughMessageId());
            if (rows == 0) {
                log.debug("skip summary write: watermark already advanced, conversationId={}, throughId={}",
                        conversationId, ctx.summaryThroughMessageId());
            } else {
                // 本地实体与库对齐，供本 turn 后续逻辑使用；不得再整实体 updateById
                conversation.setSummaryText(ctx.summaryText());
                conversation.setSummaryThroughMessageId(ctx.summaryThroughMessageId());
                conversation.setSummaryOrigin(0);
                // MEMORY_DISTILL / SUMMARY_REFRESH：失败不得影响主对话
                if (memoryJobEnqueueService != null) {
                    try {
                        memoryJobEnqueueService.onSummaryUpdated(
                                conversation, ctx.summaryThroughMessageId());
                    } catch (Exception e) {
                        log.warn("memory job enqueue failed: conversationId={}: {}",
                                conversationId, e.getMessage());
                    }
                }
            }
        }

        List<CompanionTool> usableTools = usableTools();
        boolean toolOrchestrationAvailable = toolOrchestrationAvailable(usableTools);
        String systemPrompt = buildSystemPromptForStudent(
                conversation.getStudentId(),
                conversation.getCourseId(),
                conversation.getNodeCode(),
                toolOrchestrationAvailable);

        // MEMORY 槽：memory-enabled 时按 effective top-k 注入并强化选中项
        List<String> memoryLines = loadMemoryLines(
                conversation.getStudentId(), conversation.getCourseId());

        // RETRIEVAL 槽：rag-enabled 且有命中时注入不可信包裹块
        String latestUser = latestUserContent(history);
        List<String> retrievalBlocks = loadRetrievalBlocks(
                conversation.getCourseId(),
                conversation.getNodeCode(),
                latestUser);

        // 一次学生消息的完整编排 = 一个 run；SSE 异常断开也必须 finishRun
        TraceContext traceCtx = startCompanionTrace(conversation, latestUser);
        int runStatus = AgentRunStatus.SUCCEEDED;
        String errorCode = null;
        try {
            // M9 kill switch: companion-llm-enabled=false → zero-LLM template path
            if (!isCompanionLlmEnabled()) {
                runStatus = streamDegraded(conversationId, conversation, sink, traceCtx,
                        CompanionDegradeService.REASON_LLM_DISABLED);
                return;
            }
            // 换策略（探针未通过后）与复盘会话：本 turn 的动态提示段，追加在学生动态段之后
            ReflectionCoordinator.StrategyDirective strategyDirective = resolveStrategyDirective(conversation);
            String strategySection = strategyPromptSection(conversation.getCourseId(), strategyDirective);
            if (!toolOrchestrationAvailable) {
                streamWithLegacyPath(conversationId, systemPrompt + strategySection, ctx, memoryLines,
                        retrievalBlocks, sink, traceCtx);
                return;
            }
            CompanionOrchestrator.Outcome outcome = streamWithTools(conversationId, conversation,
                    systemPrompt + strategySection + reviewPromptSection(reviewPracticeSessionId),
                    strategySection, reviewPracticeSessionId,
                    latestUser, ctx, memoryLines, retrievalBlocks, usableTools, sink, traceCtx);
            runStatus = outcome.runStatus();
            errorCode = outcome.errorCode();
            if (runStatus == AgentRunStatus.SUCCEEDED) {
                // M9：本 turn 结束后确定性判定意图，EXPLAIN 时按需追发理解探针
                CompanionIntent intent = CompanionIntentClassifier.classify(latestUser, outcome.toolsCalled());
                if (strategyDirective != null && intent == CompanionIntent.CHAT) {
                    // 本 turn 按新策略重讲 = 讲解；需再探一次以检验新讲法
                    intent = CompanionIntent.EXPLAIN;
                }
                maybeAutoProbeAfterExplain(conversation, intent,
                        probeTarget(conversation, outcome, strategyDirective), sink);
            }
        } catch (RuntimeException ex) {
            runStatus = AgentRunStatus.FAILED;
            errorCode = "UNEXPECTED";
            throw ex;
        } finally {
            finishCompanionTrace(traceCtx, runStatus, errorCode);
        }
    }

    @Override
    public List<CompanionConversationVO> listConversations(Long studentId, Long courseId, Long beforeId, Integer limit) {
        int lim = contextBuilder.clampListLimit(limit);
        List<CompanionConversation> conversations = conversationMapper.selectKeysetByStudentCourse(
                studentId, courseId, beforeId, lim);
        if (conversations == null) {
            conversations = List.of();
        }
        return conversations.stream().map(conversation -> {
            CompanionConversationVO vo = new CompanionConversationVO();
            vo.setId(conversation.getId());
            vo.setTitle(conversation.getTitle());
            vo.setNodeCode(conversation.getNodeCode());
            vo.setUpdatedAt(conversation.getUpdatedAt());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public List<CompanionMessageVO> getMessages(Long conversationId, Long beforeId, Integer limit) {
        assertConversationOwner(conversationId);
        int lim = contextBuilder.clampListLimit(limit);
        List<CompanionMessage> messages = messageMapper.selectKeysetByConversation(conversationId, beforeId, lim);
        if (messages == null) {
            messages = List.of();
        }
        // keyset 按 id DESC 取出，展示时反转为时间正序
        List<CompanionMessage> chronological = new ArrayList<>(messages);
        Collections.reverse(chronological);
        return chronological.stream().map(message -> {
            CompanionMessageVO vo = new CompanionMessageVO();
            vo.setRole(message.getRole() == ROLE_USER ? "user" : "ai");
            vo.setContent(message.getContent());
            vo.setCreatedAt(message.getCreatedAt());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Long conversationId) {
        assertConversationOwner(conversationId);
        messageMapper.delete(new LambdaQueryWrapper<CompanionMessage>()
                .eq(CompanionMessage::getConversationId, conversationId));
        conversationMapper.deleteById(conversationId);
    }

    private void streamWithLegacyPath(Long conversationId,
                                      String systemPrompt,
                                      ConversationContextBuilder.BuiltContext ctx,
                                      List<String> memoryLines,
                                      List<String> retrievalBlocks,
                                      CompanionStreamSink sink,
                                      TraceContext traceCtx) {
        ConversationContextBuilder.LegacyPrompt prompt =
                contextBuilder.toLegacyPrompt(
                        ctx,
                        memoryLines == null ? List.of() : memoryLines,
                        retrievalBlocks == null ? List.of() : retrievalBlocks);
        StringBuilder fullReply = new StringBuilder();
        int maxOut = limits.getCompanion().getMaxAiOutputChars();
        long started = System.currentTimeMillis();
        String inputDigest = "legacy|user=" + truncateForTrace(prompt.latestUserMessage(), 200);
        aiClient.stream(systemPrompt, prompt.history(), prompt.latestUserMessage(),
                truncatingTokenConsumer(fullReply, sink, maxOut));
        String reply = fullReply.toString();
        saveAiMessage(conversationId, reply);
        recordLegacyStep(traceCtx, inputDigest, reply, started);
    }

    private Consumer<String> truncatingTokenConsumer(StringBuilder fullReply,
                                                     CompanionStreamSink sink,
                                                     int maxOut) {
        final boolean[] done = {false};
        return token -> {
            if (done[0] || token == null) {
                return;
            }
            if (fullReply.length() >= maxOut) {
                done[0] = true;
                return;
            }
            int room = maxOut - fullReply.length();
            if (token.length() <= room) {
                fullReply.append(token);
                sink.token(token);
                if (fullReply.length() >= maxOut) {
                    String notice = contextBuilder.outputTruncationNotice();
                    fullReply.append(notice);
                    sink.token(notice);
                    done[0] = true;
                }
            } else {
                String piece = token.substring(0, room);
                fullReply.append(piece);
                sink.token(piece);
                String notice = contextBuilder.outputTruncationNotice();
                fullReply.append(notice);
                sink.token(notice);
                done[0] = true;
            }
        };
    }

    /**
     * 工具编排路径：组装消息与外壳回调，委托 {@link CompanionOrchestrator} 单循环真流式执行。
     */
    private CompanionOrchestrator.Outcome streamWithTools(Long conversationId,
                                                          CompanionConversation conversation,
                                                          String systemPrompt,
                                                          String fallbackPromptSuffix,
                                                          Long reviewPracticeSessionId,
                                                          String latestUser,
                                                          ConversationContextBuilder.BuiltContext ctx,
                                                          List<String> memoryLines,
                                                          List<String> retrievalBlocks,
                                                          List<CompanionTool> tools,
                                                          CompanionStreamSink sink,
                                                          TraceContext traceCtx) {
        List<Map<String, Object>> messages = contextBuilder.toToolMessages(
                systemPrompt,
                ctx,
                memoryLines == null ? List.of() : memoryLines,
                retrievalBlocks == null ? List.of() : retrievalBlocks);
        ToolContext toolContext = new ToolContext(
                conversation.getStudentId(),
                conversation.getCourseId(),
                conversation.getNodeCode(),
                reviewPracticeSessionId);
        CompanionOrchestrator.Request request = new CompanionOrchestrator.Request(
                messages,
                tools,
                toolContext,
                forcedPracticeCall(conversation, latestUser, tools),
                maxRounds(),
                maxToolCalls(),
                limits.getCompanion().getMaxAiOutputChars(),
                contextBuilder.outputTruncationNotice(),
                traceCtx);

        CompanionOrchestrator.Shell shell = new CompanionOrchestrator.Shell() {
            private String fallbackPrompt;

            @Override
            public int degrade(CompanionStreamSink out, String reason) {
                return streamDegraded(conversationId, conversation, out, traceCtx, reason);
            }

            @Override
            public void streamLegacy(CompanionStreamSink out) {
                streamWithLegacyPath(conversationId, fallbackSystemPrompt(), ctx, memoryLines,
                        retrievalBlocks, out, traceCtx);
            }

            @Override
            public String fallbackSystemPrompt() {
                if (fallbackPrompt == null) {
                    fallbackPrompt = buildSystemPromptForStudent(
                            conversation.getStudentId(),
                            conversation.getCourseId(),
                            conversation.getNodeCode(),
                            false) + (fallbackPromptSuffix == null ? "" : fallbackPromptSuffix);
                }
                return fallbackPrompt;
            }

            @Override
            public void persist(String text) {
                saveAiMessage(conversationId, text);
            }

            @Override
            public boolean cancelRequested() {
                return isRunCancelRequested(traceCtx);
            }

            @Override
            public void step(StepRecord record) {
                safeStep(traceCtx, record);
            }
        };
        return new CompanionOrchestrator(aiClient, toolGovernor, shell).run(request, sink);
    }

    private void maybeAutoProbeAfterExplain(CompanionConversation conversation,
                                            CompanionIntent intent,
                                            String nodeCode,
                                            CompanionStreamSink sink) {
        if (reflectionCoordinator == null || conversation == null) {
            return;
        }
        if (!reflectionCoordinator.shouldAutoProbe(intent)) {
            return;
        }
        if (!StringUtils.hasText(nodeCode)) {
            return;
        }
        try {
            Phase1Decision decision = Phase1Decision.of(intent, new CompanionAiClient.FinalText(""));
            reflectionCoordinator.maybeIssueProbe(
                            conversation.getStudentId(),
                            conversation.getCourseId(),
                            nodeCode,
                            decision)
                    .ifPresent(sink::probeCard);
        } catch (Exception e) {
            log.warn("auto probe after EXPLAIN failed: {}", e.getMessage());
        }
    }

    /**
     * 自动探针目标：本 turn 错题工具给出的第一误区节点 &gt; 本 turn 按新策略重讲的节点 &gt; 会话焦点节点。
     */
    static String probeTarget(CompanionConversation conversation,
                              CompanionOrchestrator.Outcome outcome,
                              ReflectionCoordinator.StrategyDirective strategyDirective) {
        String misconception = outcome == null ? null
                : outcome.turnSignals().get(CompanionOrchestrator.SIGNAL_MISCONCEPTION_NODE);
        if (StringUtils.hasText(misconception)) {
            return misconception;
        }
        if (strategyDirective != null && StringUtils.hasText(strategyDirective.nodeCode())) {
            return strategyDirective.nodeCode();
        }
        return conversation == null ? null : conversation.getNodeCode();
    }

    /** 失败不阻断对话；reflection 关闭 / 未装配时为 null。 */
    private ReflectionCoordinator.StrategyDirective resolveStrategyDirective(CompanionConversation conversation) {
        if (reflectionCoordinator == null || conversation == null) {
            return null;
        }
        try {
            return reflectionCoordinator.resolveStrategyDirective(
                    conversation.getStudentId(), conversation.getCourseId(), conversation.getNodeCode()).orElse(null);
        } catch (Exception e) {
            log.warn("strategy directive failed: conversationId={}: {}", conversation.getId(), e.getMessage());
            return null;
        }
    }

    private String strategyPromptSection(Long courseId, ReflectionCoordinator.StrategyDirective directive) {
        if (directive == null) {
            return "";
        }
        String label = directive.nodeCode();
        try {
            KgNode node = nodeMapper.selectOne(new LambdaQueryWrapper<KgNode>()
                    .eq(KgNode::getCourseId, courseId)
                    .eq(KgNode::getNodeCode, directive.nodeCode())
                    .isNull(KgNode::getArchivedAt));
            if (node != null) {
                label = nodePromptLabel(node);
            }
        } catch (Exception e) {
            log.debug("strategy node label lookup failed: {}", e.getMessage());
        }
        return StrategySwitcher.reexplainDirective(directive.strategy(), label);
    }

    /** 复盘会话（已由控制器校验归属）：提示先读错题；仅工具路径注入。 */
    static String reviewPromptSection(Long reviewPracticeSessionId) {
        if (reviewPracticeSessionId == null) {
            return "";
        }
        return "## 本轮复盘\n"
                + "学生点击了「复盘错题」，要复盘刚完成的那次练习（会话 #" + reviewPracticeSessionId + "）："
                + "请先调用 get_recent_mistakes（无需传 practiceSessionId，默认即该会话），再按「错因辅导」规则逐题讲清误区。\n\n";
    }

    private int streamDegraded(Long conversationId,
                               CompanionConversation conversation,
                               CompanionStreamSink sink,
                               TraceContext traceCtx,
                               String reason) {
        if (degradeService == null || conversation == null) {
            String fallback = "学习伴侣暂时不可用，请稍后再试。优先完成今日复习与薄弱点练习。";
            sink.token(fallback);
            saveAiMessage(conversationId, fallback);
            return AgentRunStatus.DEGRADED;
        }
        CompanionDegradeService.DegradeResult result = degradeService.degrade(
                conversation.getStudentId(),
                conversation.getCourseId(),
                conversation.getNodeCode(),
                reason,
                traceCtx);
        if (result.text() != null) {
            sink.token(result.text());
            saveAiMessage(conversationId, result.text());
        }
        if (result.practiceCard() != null) {
            sink.practiceCard(result.practiceCard());
        }
        return AgentRunStatus.DEGRADED;
    }

    private boolean isCompanionLlmEnabled() {
        return featureFlags == null || featureFlags.isCompanionLlmEnabled();
    }

    /** M10：step 边界检查 cancel_requested_at。 */
    private boolean isRunCancelRequested(TraceContext traceCtx) {
        return planCancelGuard != null && planCancelGuard.isCancelRequested(traceCtx);
    }

    /**
     * 自然语言练习意图：合成 start_practice 调用，由编排器先执行再一轮 NONE 总结。
     *
     * @return 合成调用；无练习意图 / 路由关闭 / 无 start_practice 工具时为 null
     */
    private CompanionAiClient.ToolCall forcedPracticeCall(CompanionConversation conversation,
                                                          String latestUser,
                                                          List<CompanionTool> tools) {
        if (toolProperties == null || !toolProperties.isIntentRoutingEnabled()) {
            return null;
        }
        if (tools == null || tools.stream().noneMatch(tool -> "start_practice".equals(tool.name()))) {
            return null;
        }
        List<PracticeIntentDetector.NodeRef> nodeRefs = loadCourseNodeRefs(conversation.getCourseId());
        String focusCode = conversation.getNodeCode();
        String focusName = resolveFocusName(focusCode, nodeRefs);

        PracticeIntent intent = PracticeIntentDetector.detect(latestUser, nodeRefs, focusCode, focusName)
                .orElse(null);
        if (intent == null) {
            return null;
        }
        return new CompanionAiClient.ToolCall(
                "intent-start-practice",
                "start_practice",
                buildStartPracticeArgsJson(intent));
    }

    private List<PracticeIntentDetector.NodeRef> loadCourseNodeRefs(Long courseId) {
        if (courseId == null) {
            return List.of();
        }
        List<KgNode> allNodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .isNull(KgNode::getArchivedAt));
        if (allNodes == null || allNodes.isEmpty()) {
            return List.of();
        }
        return allNodes.stream()
                .filter(Objects::nonNull)
                .map(node -> new PracticeIntentDetector.NodeRef(node.getNodeCode(), node.getName()))
                .toList();
    }

    private static String resolveFocusName(String focusCode, List<PracticeIntentDetector.NodeRef> nodeRefs) {
        if (focusCode == null || focusCode.isBlank() || nodeRefs == null) {
            return null;
        }
        for (PracticeIntentDetector.NodeRef ref : nodeRefs) {
            if (ref != null && focusCode.equals(ref.nodeCode())) {
                return ref.name();
            }
        }
        return null;
    }

    private static String buildStartPracticeArgsJson(PracticeIntent intent) {
        StringBuilder sb = new StringBuilder("{");
        boolean hasCode = intent.nodeCode() != null && !intent.nodeCode().isBlank();
        boolean hasName = intent.nodeName() != null && !intent.nodeName().isBlank();
        if (hasCode) {
            sb.append("\"nodeCode\":\"").append(CompanionOrchestrator.escapeJson(intent.nodeCode())).append('"');
        } else if (hasName) {
            sb.append("\"nodeName\":\"").append(CompanionOrchestrator.escapeJson(intent.nodeName())).append('"');
        }
        if (hasCode || hasName) {
            sb.append(',');
        }
        sb.append("\"size\":").append(intent.size()).append('}');
        return sb.toString();
    }

    private boolean toolOrchestrationAvailable(List<CompanionTool> tools) {
        return toolGovernor != null
                && toolProperties != null
                && toolProperties.isEnabled()
                && maxRounds() > 0
                && maxToolCalls() > 0
                && tools != null
                && !tools.isEmpty();
    }

    private List<CompanionTool> usableTools() {
        if (companionTools == null || companionTools.isEmpty()) {
            return List.of();
        }
        boolean memoryOn = isMemoryEnabled();
        boolean ragOn = isRagEnabled();
        boolean reflectionOn = isReflectionEnabled();
        boolean planOn = isPlanEnabled();
        // Lexicographic by tool name so schema/dict order is stable across injectors.
        return companionTools.stream()
                .filter(Objects::nonNull)
                .filter(tool -> tool.schema() != null)
                .filter(tool -> memoryOn || !isMemoryTool(tool.name()))
                .filter(tool -> ragOn || !isRagTool(tool.name()))
                .filter(tool -> reflectionOn || !isReflectionTool(tool.name()))
                .filter(tool -> planOn || !isPlanTool(tool.name()))
                .sorted(Comparator.comparing(CompanionTool::name, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private static boolean isMemoryTool(String name) {
        return "recall_student_context".equals(name) || "record_observation".equals(name);
    }

    private static boolean isRagTool(String name) {
        return "search_materials".equals(name);
    }

    private static boolean isReflectionTool(String name) {
        return "probe_understanding".equals(name);
    }

    private static boolean isPlanTool(String name) {
        return "draft_study_plan".equals(name) || "get_plan_progress".equals(name);
    }

    private boolean isReflectionEnabled() {
        return featureFlags == null || featureFlags.isReflectionEnabled();
    }

    private boolean isMemoryEnabled() {
        return featureFlags == null || featureFlags.isMemoryEnabled();
    }

    private boolean isRagEnabled() {
        return featureFlags == null || featureFlags.isRagEnabled();
    }

    private boolean isPlanEnabled() {
        return featureFlags == null || featureFlags.isPlanEnabled();
    }

    /**
     * 加载 MEMORY 槽行；flag 关 / 服务缺省 / 失败时返回空列表（不阻断对话）。
     */
    List<String> loadMemoryLines(Long studentId, Long courseId) {
        if (!isMemoryEnabled() || studentMemoryService == null) {
            return List.of();
        }
        try {
            List<String> lines = studentMemoryService.loadInjectLines(studentId, courseId);
            return lines == null ? List.of() : lines;
        } catch (Exception e) {
            log.warn("memory inject failed: studentId={} courseId={}: {}",
                    studentId, courseId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 加载 RETRIEVAL 槽块；flag 关 / 服务缺省 / 无命中时返回空列表（不阻断对话）。
     * <p>有命中时 formatInjectionBlocks 含不可信包裹；空命中不注入占位（避免污染无关轮次）。
     */
    List<String> loadRetrievalBlocks(Long courseId, String nodeCode, String userQuery) {
        if (!isRagEnabled() || retrievalService == null || courseId == null) {
            return List.of();
        }
        if (userQuery == null || userQuery.isBlank()) {
            return List.of();
        }
        try {
            int topK = limits != null && limits.getRag() != null
                    ? Math.max(1, limits.getRag().getInjectTopK())
                    : 4;
            List<String> nodeCodes = StringUtils.hasText(nodeCode) ? List.of(nodeCode.trim()) : List.of();
            RetrievalService.RetrievalResult result = retrievalService.search(
                    courseId, nodeCodes, userQuery.trim(), topK, true);
            if (result == null || result.isEmpty()) {
                return List.of();
            }
            List<String> blocks = retrievalService.formatInjectionBlocks(result);
            return blocks == null ? List.of() : blocks;
        } catch (Exception e) {
            log.warn("retrieval inject failed: courseId={} nodeCode={}: {}",
                    courseId, nodeCode, e.getMessage());
            return List.of();
        }
    }

    private int maxRounds() {
        return toolProperties == null ? 0 : Math.max(0, toolProperties.getMaxRounds());
    }

    private int maxToolCalls() {
        return toolProperties == null ? 0 : Math.max(0, toolProperties.getMaxToolCalls());
    }

    private void saveAiMessage(Long conversationId, String content) {
        CompanionMessage aiMessage = new CompanionMessage();
        aiMessage.setConversationId(conversationId);
        aiMessage.setRole(ROLE_AI);
        aiMessage.setContent(content);
        aiMessage.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(aiMessage);
    }

    // ── Agent trace helpers (Task 7；失败不得打断主流程) ──────────────────────

    private TraceContext startCompanionTrace(CompanionConversation conversation, String firstInput) {
        if (agentTraceService == null || conversation == null) {
            return TraceContext.NOOP;
        }
        try {
            Long studentId = conversation.getStudentId() == null ? 0L : conversation.getStudentId();
            Long courseId = conversation.getCourseId() == null ? 0L : conversation.getCourseId();
            TraceContext ctx = agentTraceService.startRun(StartRunCmd.of(
                    studentId,
                    courseId,
                    conversation.getId(),
                    "COMPANION_CHAT",
                    currentModelForTrace(),
                    firstInput));
            return ctx == null ? TraceContext.NOOP : ctx;
        } catch (Exception e) {
            log.warn("startCompanionTrace failed, continuing with NOOP: {}", e.toString());
            return TraceContext.NOOP;
        }
    }

    /**
     * 本次 run 实际会调用的模型名，写入 agent_run.model（管理后台「AI 按模型」统计依赖它）。
     * <p>每次现取 {@code AiProperties.getModel()}：系统配置可运行时热改 wenjin.ai.model，不能用启动值。
     * 零 LLM 模板路径（companion-llm-enabled=false）不会调用模型，保持 null；
     * 未配置 / 空白模型名同样记 null，不写空串。
     */
    private String currentModelForTrace() {
        if (aiProperties == null || !isCompanionLlmEnabled()) {
            return null;
        }
        String model = aiProperties.getModel();
        return model == null || model.isBlank() ? null : model.trim();
    }

    private void finishCompanionTrace(TraceContext ctx, int runStatus, String errorCode) {
        if (agentTraceService == null || ctx == null) {
            return;
        }
        try {
            agentTraceService.finishRun(ctx, runStatus, errorCode);
        } catch (Exception e) {
            log.warn("finishCompanionTrace failed for runId={}: {}", ctx.runId(), e.toString());
        }
    }

    private void safeStep(TraceContext ctx, StepRecord record) {
        if (agentTraceService == null || ctx == null || record == null) {
            return;
        }
        try {
            agentTraceService.step(ctx, record);
        } catch (Exception e) {
            log.warn("agentTrace step failed for runId={}: {}", ctx.runId(), e.toString());
        }
    }

    /** legacy（工具关闭）路径的单次流式调用；无 usage → chars/2 估算并标记 tokens_estimated。 */
    private void recordLegacyStep(TraceContext ctx, String inputDigest, String output, long startedMs) {
        int tokensIn = CompanionOrchestrator.estimateTokens(inputDigest);
        int tokensOut = CompanionOrchestrator.estimateTokens(output);
        if (ctx != null && !ctx.isNoop()) {
            ctx.markTokensEstimated();
        }
        safeStep(ctx, new StepRecord(
                AgentStepType.LLM_CALL,
                "phase2",
                AgentStepStatus.OK,
                inputDigest,
                output,
                tokensIn,
                tokensOut,
                elapsedMs(startedMs),
                null));
    }

    private static String latestUserContent(List<CompanionMessage> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            CompanionMessage m = history.get(i);
            if (m != null && m.getRole() != null && m.getRole() == ROLE_USER) {
                return m.getContent();
            }
        }
        CompanionMessage last = history.get(history.size() - 1);
        return last == null ? null : last.getContent();
    }

    private static int elapsedMs(long startedMs) {
        long d = System.currentTimeMillis() - startedMs;
        if (d < 0) {
            return 0;
        }
        return (int) Math.min(d, Integer.MAX_VALUE);
    }

    private static String truncateForTrace(String value, int max) {
        if (value == null) {
            return null;
        }
        if (max <= 0 || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }

    private void assertConversationOwner(Long conversationId) {
        Long currentUserId = CurrentUser.get();
        if (currentUserId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage());
        }
        CompanionConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !currentUserId.equals(conversation.getStudentId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问他人会话");
        }
        courseService.assertAccessibleByStudent(currentUserId, conversation.getCourseId());
    }

    private String buildTitle(CompanionChatRequest req) {
        String message = req.getMessage();
        String truncated = message.length() > 20 ? message.substring(0, 20) + "..." : message;

        if (req.getNodeCode() != null) {
            KgNode node = nodeMapper.selectOne(
                    new LambdaQueryWrapper<KgNode>()
                            .eq(KgNode::getCourseId, req.getCourseId())
                            .eq(KgNode::getNodeCode, req.getNodeCode())
                            .isNull(KgNode::getArchivedAt));
            if (node != null) {
                return node.getName() + "：" + truncated;
            }
        }
        return truncated;
    }

    private String buildSystemPromptForStudent(Long studentId,
                                               Long courseId,
                                               String nodeCode,
                                               boolean useToolPrompt) {
        PromptContext context = buildPromptContext(studentId, courseId, nodeCode, !useToolPrompt);
        if (useToolPrompt) {
            return buildSystemPrompt(
                    context.courseName(),
                    context.whitelist(),
                    List.of(),
                    context.targetNode(),
                    context.currentStep(),
                    context.focusNodeCode(),
                    context.focusNodeName(),
                    context.diagnostic());
        }
        return buildLegacySystemPrompt(
                context.courseName(),
                context.whitelist(),
                context.weakPoints(),
                context.targetNode(),
                context.currentStep(),
                context.focusNodeCode(),
                context.focusNodeName(),
                context.diagnostic());
    }

    private PromptContext buildPromptContext(Long studentId,
                                             Long courseId,
                                             String nodeCode,
                                             boolean includeWeakPoints) {
        List<KgNode> allNodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .isNull(KgNode::getArchivedAt));
        List<String> whitelist = allNodes.stream().map(CompanionServiceImpl::nodePromptLabel).collect(Collectors.toList());

        LearningPathVO path = null;
        try {
            path = pathService.getCurrent(studentId, courseId);
        } catch (Exception ignored) {
        }

        DiagnosticResultVO diagnostic = null;
        try {
            diagnostic = diagnosticResultService.getResult(studentId, courseId);
        } catch (Exception ignored) {
        }

        LearningPathVO.NodeRef targetNode = path == null ? null : path.getTargetNode();
        LearningPathVO.StepVO currentStep = null;
        if (path != null && path.getSteps() != null) {
            currentStep = path.getSteps().stream()
                    .filter(step -> step.getStatus() == 0)
                    .findFirst()
                    .orElse(null);
        }

        String focusNodeCode = nodeCode;
        String focusNodeName = null;
        if (focusNodeCode != null) {
            KgNode focusNode = nodeMapper.selectOne(
                    new LambdaQueryWrapper<KgNode>()
                            .eq(KgNode::getCourseId, courseId)
                            .eq(KgNode::getNodeCode, focusNodeCode)
                            .isNull(KgNode::getArchivedAt));
            if (focusNode != null) {
                focusNodeName = focusNode.getName();
            }
        }

        Course course = courseMapper.selectById(courseId);
        String courseName = course != null && course.getName() != null && !course.getName().isBlank()
                ? course.getName()
                : null;

        List<String> weakPoints = includeWeakPoints ? loadWeakPoints(studentId, courseId) : List.of();
        return new PromptContext(courseName, whitelist, weakPoints, targetNode, currentStep, focusNodeCode, focusNodeName, diagnostic);
    }

    private List<String> loadWeakPoints(Long studentId, Long courseId) {
        List<StudentMastery> masteries = masteryMapper.selectList(
                new LambdaQueryWrapper<StudentMastery>()
                        .eq(StudentMastery::getStudentId, studentId)
                        .eq(StudentMastery::getCourseId, courseId));

        List<StudentMastery> weakMasteries = masteries.stream()
                .filter(mastery -> mastery.getMasteryScore() != null)
                .filter(mastery -> decayCalculator.effectiveLevelOf(decayCalculator.effectiveScoreOf(mastery)) < 2)
                .toList();

        if (weakMasteries.isEmpty()) {
            return List.of();
        }

        List<Long> nodeIds = weakMasteries.stream().map(StudentMastery::getNodeId).toList();
        // 归档节点不进入弱掌握上下文，避免 AI 推荐已下线知识点
        Map<Long, KgNode> nodeMap = nodeMapper.selectBatchIds(nodeIds).stream()
                .filter(Objects::nonNull)
                .filter(node -> node.getArchivedAt() == null)
                .collect(Collectors.toMap(KgNode::getId, node -> node, (left, right) -> left));

        return weakMasteries.stream()
                .map(mastery -> {
                    KgNode node = nodeMap.get(mastery.getNodeId());
                    if (node == null) {
                        return null;
                    }
                    double effective = decayCalculator.effectiveScoreOf(mastery);
                    return node.getName() + "（掌握度 " + Math.round(effective) + "）";
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private static String nodePromptLabel(KgNode node) {
        if (node == null) {
            return "";
        }
        String name = node.getName() == null ? "" : node.getName();
        String nodeCode = node.getNodeCode();
        if (nodeCode == null || nodeCode.isBlank()) {
            return name;
        }
        return name + "（nodeCode: " + nodeCode + "）";
    }

    /**
     * 工具路径系统提示：静态段在前（人设、对话规则、工具使用、课程知识点白名单），学生动态段在后
     * （诊断根因、当前学习路径、当前焦点节点），使同一课程下不同学生/轮次共享可缓存前缀。
     */
    public static String buildSystemPrompt(String courseName,
                                           List<String> whitelist,
                                           List<String> weakPoints,
                                           LearningPathVO.NodeRef targetNode,
                                           LearningPathVO.StepVO currentStep,
                                           String focusNodeCode,
                                           String focusNodeName,
                                           DiagnosticResultVO diagnostic) {
        String cn = (courseName == null || courseName.isBlank()) ? "课程" : courseName;
        StringBuilder sb = new StringBuilder();

        // ── 静态段 ──
        sb.append("你是「问津」的 AI 学习伙伴，专注于帮助学生理解和掌握《")
                .append(cn)
                .append("》课程的知识点。\n\n");

        sb.append("## 对话规则\n");
        sb.append("- 只回答《").append(cn).append("》课程范围内的问题；超出范围时，礼貌地把话题引回当前焦点节点或学习路径目标。\n");
        sb.append("- 采用启发式教学：不要一次性给出完整答案。先点出关键，再追问一个小问题确认理解；学生卡住时再分步提示。\n");
        sb.append("- 当学生索要「直接答案 / 标准答案 / 最终结论」时：只给分步引导与提示，不给出完整最终答案；先让学生尝试一步。\n");
        sb.append("- 关注优先级：诊断根因 > 当前学习路径 > 当前焦点节点（见文末学生信息）。\n");
        sb.append("- 回答控制在 3 到 6 句，口语化、具体、适合聊天场景。\n\n");

        sb.append("## 工具使用\n");
        sb.append("- 涉及掌握度、薄弱点、证据时间时，先通过工具查询，再回答。\n");
        sb.append("- 涉及节点详情、前置关系、后继关系时，先通过工具获取节点详情，再组织解释。\n");
        sb.append("- 需要发起练习、推荐练习或确认练习入口时，先通过工具获取练习信息。\n");
        sb.append("- query_mastery：查掌握度/趋势；get_node_detail：节点详情与前后继；start_practice：发起练习。\n");
        sb.append("- trace_prerequisites：追溯前置链；get_learning_frontier：可学前沿；get_review_queue：今日复习队列；schedule_review：把节点钉入今日复习。\n");
        sb.append("- recall_student_context：按关键词检索学生长期记忆；record_observation：记录 SEMANTIC/PROCEDURAL 观察（每轮有限）。\n");
        sb.append("- search_materials：按关键词检索课程资料片段（附出处）；probe_understanding：讲解后发 1 道理解探针题（不改掌握度）。\n");
        sb.append("- draft_study_plan：起草备考学习计划（仅草稿，需学生确认）；get_plan_progress：查看当前学习计划进度与今日剩余任务。\n");
        sb.append("- get_recent_mistakes：读取学生近期错题（所选 vs 正确选项）及错误选项指向的误区节点。\n");
        sb.append("- 只能调用本轮接口实际提供的工具；未提供的工具视为当前不可用。\n");
        sb.append("- 用户只说节点名称时，先从课程知识点范围匹配名称；能确定 nodeCode 就用 nodeCode，否则传精确 nodeName 让服务端解析。\n");
        sb.append("- 禁止编造掌握度；没有工具证据时，要明确说明暂未查询到。\n");
        sb.append("- 工具只能通过接口提供的 function/tool_calls 通道调用；禁止在回复正文中输出 DSML、XML、tool_calls、invoke、伪标签或任何工具协议原文。\n");
        sb.append("- 工具执行完成后，只用自然语言总结结果，不要重复工具参数或协议。\n\n");

        sb.append("## 错因辅导\n");
        sb.append("- 学生要求复盘错题、说自己做错了或不懂为什么错时，先调用 get_recent_mistakes，再回答。\n");
        sb.append("- 针对误区节点（misconceptionNodes / topMisconceptions）讲清「为什么会选错」：对比所选选项与正确选项背后的概念差别，指出混淆点。\n");
        sb.append("- 不要直接念答案或照搬解析；先让学生说出当时的想法，再引导其自己发现错误。\n");
        sb.append("- 讲完后系统会自动就误区节点出一道理解探针题，无需你另行出题。\n\n");

        sb.append("## 课程知识点范围\n");
        if (whitelist != null) {
            for (String nodeName : whitelist) {
                sb.append("- ").append(nodeName).append("\n");
            }
        }
        sb.append("\n");

        // ── 学生动态段 ──
        appendRootCauseSection(sb, diagnostic);

        if (targetNode != null) {
            sb.append("## 当前学习路径\n");
            sb.append("目标节点：").append(targetNode.getName()).append("（").append(targetNode.getNodeCode()).append("）\n");
            if (currentStep != null) {
                sb.append("当前步骤：").append(currentStep.getName()).append("（").append(currentStep.getNodeCode()).append("）\n");
            }
            sb.append("\n");
        }

        if (focusNodeCode != null && focusNodeName != null) {
            sb.append("## 当前焦点节点\n");
            sb.append("学生正在学习：").append(focusNodeName).append("（").append(focusNodeCode).append("）\n\n");
        }

        return sb.toString();
    }

    private static String buildLegacySystemPrompt(String courseName,
                                                  List<String> whitelist,
                                                  List<String> weakPoints,
                                                  LearningPathVO.NodeRef targetNode,
                                                  LearningPathVO.StepVO currentStep,
                                                  String focusNodeCode,
                                                  String focusNodeName,
                                                  DiagnosticResultVO diagnostic) {
        String cn = (courseName == null || courseName.isBlank()) ? "课程" : courseName;
        StringBuilder sb = new StringBuilder();

        sb.append("你是「问津」的 AI 学习伴侣，专注于帮助学生理解和掌握《").append(cn).append("》课程的知识点。\n\n");

        appendRootCauseSection(sb, diagnostic);

        sb.append("## 课程知识点范围\n");
        if (whitelist != null) {
            for (String nodeName : whitelist) {
                sb.append("- ").append(nodeName).append("\n");
            }
        }
        sb.append("\n");

        if (weakPoints != null && !weakPoints.isEmpty()) {
            sb.append("## 学生薄弱点\n");
            for (String weakPoint : weakPoints) {
                sb.append("- ").append(weakPoint).append("\n");
            }
            sb.append("\n");
        }

        if (targetNode != null) {
            sb.append("## 当前学习路径\n");
            sb.append("目标节点：").append(targetNode.getName()).append("（").append(targetNode.getNodeCode()).append("）\n");
            if (currentStep != null) {
                sb.append("当前步骤：").append(currentStep.getName()).append("（").append(currentStep.getNodeCode()).append("）\n");
            }
            sb.append("\n");
        }

        if (focusNodeCode != null && focusNodeName != null) {
            sb.append("## 当前焦点节点\n");
            sb.append("学生正在学习：").append(focusNodeName).append("（").append(focusNodeCode).append("）\n\n");
        }

        sb.append("## 对话规则\n");
        sb.append("- 只回答《").append(cn).append("》课程范围内的问题；超出范围时礼貌地把话题引导回当前焦点节点或学习路径目标。\n");
        sb.append("- 采用启发式教学：不要一次性给出完整答案。先一句话点出关键，再反问一个小问题确认理解；学生卡住时再分步提示，逐步逼近答案。\n");
        sb.append("- 当学生索要「直接答案 / 标准答案 / 最终结论」时：只给分步引导与提示，不给出完整最终答案；先让学生尝试一步。\n");
        sb.append("- 关注优先级：诊断根因 > 学生薄弱点 > 当前学习路径。\n");
        sb.append("- 回答控制在 3–5 句、口语化，适合聊天气泡；多用具体例子，少长篇理论。\n");
        sb.append("- 不要编造学生未学内容的掌握情况，只依据上文提供的诊断/掌握度信息。\n");

        return sb.toString();
    }

    private static void appendRootCauseSection(StringBuilder sb, DiagnosticResultVO diagnostic) {
        if (diagnostic == null || !diagnostic.isHasWeakness()) {
            return;
        }
        DiagnosticResultVO.RootCause rootCause = diagnostic.getRootCause();
        DiagnosticResultVO.NodeRef stuckNode = diagnostic.getStuckNode();
        if (rootCause == null || stuckNode == null || rootCause.isSelf()) {
            return;
        }

        sb.append("## 诊断根因\n");
        sb.append("该学生最近一次诊断显示：当前卡点是「")
                .append(stuckNode.getName())
                .append("」，但更可能的前置根因是「")
                .append(rootCause.getName())
                .append("」掌握薄弱（掌握度 ")
                .append(fmtScore(rootCause.getMasteryScore()))
                .append("）。\n");
        if (diagnostic.getChain() != null && !diagnostic.getChain().isEmpty()) {
            String chain = diagnostic.getChain().stream()
                    .map(DiagnosticResultVO.ChainNode::getName)
                    .collect(Collectors.joining(" -> "));
            sb.append("回溯链：").append(chain).append("。\n");
        }
        sb.append("当学生问到卡点或其下游内容时，优先提醒问题可能出在更前面的「")
                .append(rootCause.getName())
                .append("」，先引导补前置，再回到当前卡点。\n\n");
    }

    private static String fmtScore(Double score) {
        return score == null ? "暂无" : String.valueOf(Math.round(score));
    }

    private static CompanionToolProperties disabledToolProperties() {
        CompanionToolProperties properties = new CompanionToolProperties();
        properties.setEnabled(false);
        return properties;
    }

    private record PromptContext(String courseName,
                                 List<String> whitelist,
                                 List<String> weakPoints,
                                 LearningPathVO.NodeRef targetNode,
                                 LearningPathVO.StepVO currentStep,
                                 String focusNodeCode,
                                 String focusNodeName,
                                 DiagnosticResultVO diagnostic) {
    }
}
