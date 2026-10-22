package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.AiProperties;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.LearningPathVO;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.entity.CompanionMessage;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.CompanionConversationMapper;
import com.wenjin.mapper.CompanionMessageMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.companion.CompanionTool;
import com.wenjin.service.companion.ToolContext;
import com.wenjin.service.companion.ToolExecutionResult;
import com.wenjin.service.companion.ToolExecutionState;
import com.wenjin.service.companion.ToolGovernor;
import com.wenjin.service.trace.AgentTraceService;
import com.wenjin.service.trace.StartRunCmd;
import com.wenjin.service.trace.StepRecord;
import com.wenjin.service.trace.TraceContext;
import com.wenjin.support.AgentRunStatus;
import com.wenjin.support.AgentStepStatus;
import com.wenjin.support.AgentStepType;
import com.wenjin.support.ConversationContextBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Companion 埋点：工具路径每轮一个 llm_round + TOOL_CALL/GUARD；legacy 路径 phase2；run 边界（try/finally finishRun）。
 */
@ExtendWith(MockitoExtension.class)
class CompanionTraceTest {

    private static final int ROLE_USER = 1;

    @Mock private CompanionConversationMapper conversationMapper;
    @Mock private CompanionMessageMapper messageMapper;
    @Mock private KgNodeMapper nodeMapper;
    @Mock private StudentMasteryMapper masteryMapper;
    @Mock private CourseMapper courseMapper;
    @Mock private PathService pathService;
    @Mock private DiagnosticResultService diagnosticResultService;
    @Mock private CompanionAiClient aiClient;
    @Mock private ToolGovernor toolGovernor;
    @Mock private CourseService courseService;
    @Mock private AgentTraceService agentTraceService;

    private CompanionServiceImpl service;
    private TraceContext activeCtx;

    @BeforeEach
    void setUp() {
        LimitsProperties limits = new LimitsProperties();
        service = new CompanionServiceImpl(
                conversationMapper,
                messageMapper,
                nodeMapper,
                masteryMapper,
                courseMapper,
                pathService,
                diagnosticResultService,
                aiClient,
                makeCalculator(),
                courseService,
                new ConversationContextBuilder(limits),
                limits);
        ReflectionTestUtils.setField(service, "agentTraceService", agentTraceService);

        activeCtx = TraceContext.active(99L);
        // lenient: some cases null out the service or override startRun
        lenient().when(agentTraceService.startRun(any(StartRunCmd.class))).thenReturn(activeCtx);
    }

    @Test
    @DisplayName("tools path: startRun + llm_round + TOOL_CALL + llm_round + finishRun SUCCEEDED")
    void streamReplyWithTools_recordsFullTrace() throws Exception {
        Long conversationId = 700L;
        stubConversation(conversationId, "查询我的掌握度");
        configureTools(true, List.of(fakeTool("query_mastery", "正在查询…")));

        scriptTurns(
                turn(null, new CompanionAiClient.ToolCall("call-1", "query_mastery", "{\"nodeCode\":\"KT12\"}")),
                turn(null, null, "根据掌握度来看…"));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("call-1", "query_mastery", true, "{\"items\":[]}"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, t -> { });

        ArgumentCaptor<StartRunCmd> startCap = ArgumentCaptor.forClass(StartRunCmd.class);
        verify(agentTraceService, times(1)).startRun(startCap.capture());
        StartRunCmd start = startCap.getValue();
        assertThat(start.studentId()).isEqualTo(1L);
        assertThat(start.courseId()).isEqualTo(10L);
        assertThat(start.conversationId()).isEqualTo(conversationId);
        assertThat(start.kind()).isEqualTo("COMPANION_CHAT");
        assertThat(start.firstInput()).contains("查询我的掌握度");

        ArgumentCaptor<StepRecord> stepCap = ArgumentCaptor.forClass(StepRecord.class);
        verify(agentTraceService, times(3)).step(eq(activeCtx), stepCap.capture());
        List<StepRecord> steps = stepCap.getAllValues();

        // one LLM_CALL per round: tool round, TOOL_CALL, streamed answer round
        List<String> names = steps.stream().map(StepRecord::name).collect(Collectors.toList());
        assertThat(names).containsExactly("llm_round", "query_mastery", "llm_round");
        assertThat(names).doesNotContain("phase1", "phase2");

        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode r0 = om.readTree(steps.get(0).outputJson());
        assertThat(r0.path("round").asInt()).isZero();
        assertThat(r0.path("toolCalls").get(0).asText()).isEqualTo("query_mastery");
        assertThat(r0.path("finishReason").asText()).isEqualTo("tool_calls");
        com.fasterxml.jackson.databind.JsonNode r1 = om.readTree(steps.get(2).outputJson());
        assertThat(r1.path("round").asInt()).isEqualTo(1);
        assertThat(r1.path("toolCalls")).isEmpty();
        assertThat(r1.path("contentChars").asInt()).isEqualTo("根据掌握度来看…".length());
        assertThat(r1.path("finishReason").asText()).isEqualTo("stop");

        StepRecord toolStep = steps.stream()
                .filter(s -> AgentStepType.TOOL_CALL.equals(s.stepType()))
                .findFirst()
                .orElseThrow();
        assertThat(toolStep.name()).isEqualTo("query_mastery");
        assertThat(toolStep.status()).isEqualTo(AgentStepStatus.OK);
        assertThat(toolStep.inputJson()).contains("KT12");
        assertThat(toolStep.outputJson()).contains("items");
        // success path must not be recorded as GUARD
        assertThat(steps.stream().noneMatch(s -> AgentStepType.GUARD.equals(s.stepType()))).isTrue();

        StepRecord answerRound = steps.get(2);
        assertThat(answerRound.stepType()).isEqualTo(AgentStepType.LLM_CALL);
        assertThat(answerRound.status()).isEqualTo(AgentStepStatus.OK);
        // chars/2 estimate when the provider reports no usage
        assertThat(answerRound.tokensOut()).isEqualTo("根据掌握度来看…".length() / 2);
        assertThat(activeCtx.tokensEstimated()).isEqualTo(1);

        verify(agentTraceService, times(1)).finishRun(eq(activeCtx), eq(AgentRunStatus.SUCCEEDED), isNull());
    }

    @Test
    @DisplayName("governor budget intercept → GUARD step (not TOOL_CALL)")
    void streamReplyWithTools_budgetExceeded_recordsGuardStep() {
        Long conversationId = 710L;
        stubConversation(conversationId, "再查一次掌握度");
        configureTools(true, List.of(fakeTool("query_mastery", "正在查询…")));

        scriptTurns(
                turn(null, new CompanionAiClient.ToolCall("call-budget", "query_mastery", "{\"nodeCode\":\"KT12\"}")),
                turn(null, null, "预算已满…"));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult(
                        "call-budget",
                        "query_mastery",
                        false,
                        "{\"ok\":false,\"code\":\"TOOL_BUDGET_EXCEEDED\",\"message\":\"本轮工具调用次数已达上限\",\"retryable\":false}",
                        null,
                        true));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, t -> { });

        ArgumentCaptor<StepRecord> stepCap = ArgumentCaptor.forClass(StepRecord.class);
        verify(agentTraceService, times(3)).step(eq(activeCtx), stepCap.capture());
        List<StepRecord> steps = stepCap.getAllValues();

        StepRecord guard = steps.stream()
                .filter(s -> AgentStepType.GUARD.equals(s.stepType()))
                .findFirst()
                .orElseThrow();
        assertThat(guard.name()).isEqualTo("query_mastery");
        assertThat(guard.status()).isEqualTo(AgentStepStatus.FAILED);
        assertThat(guard.inputJson()).contains("KT12");
        assertThat(guard.outputJson()).contains("TOOL_BUDGET_EXCEEDED");
        assertThat(steps.stream().noneMatch(s -> AgentStepType.TOOL_CALL.equals(s.stepType()))).isTrue();

        verify(agentTraceService).finishRun(eq(activeCtx), eq(AgentRunStatus.SUCCEEDED), isNull());
    }

    @Test
    @DisplayName("governor unknown tool intercept → GUARD step named after tool")
    void streamReplyWithTools_unknownTool_recordsGuardStep() {
        Long conversationId = 711L;
        stubConversation(conversationId, "调用未知工具");
        configureTools(true, List.of(fakeTool("query_mastery", "正在查询…")));

        scriptTurns(
                turn(null, new CompanionAiClient.ToolCall("call-unk", "missing_tool", "{}")),
                turn(null, null, "无法调用…"));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult(
                        "call-unk",
                        "missing_tool",
                        false,
                        "{\"ok\":false,\"code\":\"TOOL_NOT_ALLOWED\",\"message\":\"工具未授权或不存在: missing_tool\",\"retryable\":false}",
                        null,
                        true));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, t -> { });

        ArgumentCaptor<StepRecord> stepCap = ArgumentCaptor.forClass(StepRecord.class);
        verify(agentTraceService, atLeastOnce()).step(eq(activeCtx), stepCap.capture());

        StepRecord guard = stepCap.getAllValues().stream()
                .filter(s -> AgentStepType.GUARD.equals(s.stepType()))
                .findFirst()
                .orElseThrow();
        assertThat(guard.name()).isEqualTo("missing_tool");
        assertThat(guard.status()).isEqualTo(AgentStepStatus.FAILED);
        assertThat(guard.outputJson()).contains("TOOL_NOT_ALLOWED");
    }

    @Test
    @DisplayName("legacy path (tools off): one run with phase2 only + finishRun")
    void streamReplyLegacy_recordsPhase2Only() {
        Long conversationId = 701L;
        stubConversation(conversationId, "什么是二次函数？");
        configureTools(false, List.of());

        doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(3);
            onToken.accept("二次函数是…");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, t -> { });

        verify(agentTraceService, times(1)).startRun(any(StartRunCmd.class));

        ArgumentCaptor<StepRecord> stepCap = ArgumentCaptor.forClass(StepRecord.class);
        verify(agentTraceService, times(1)).step(eq(activeCtx), stepCap.capture());
        StepRecord phase2 = stepCap.getValue();
        assertThat(phase2.stepType()).isEqualTo(AgentStepType.LLM_CALL);
        assertThat(phase2.name()).isEqualTo("phase2");
        assertThat(phase2.status()).isEqualTo(AgentStepStatus.OK);
        assertThat(phase2.outputJson()).contains("二次函数");
        assertThat(phase2.tokensOut()).isEqualTo("二次函数是…".length() / 2);

        verify(agentTraceService).finishRun(eq(activeCtx), eq(AgentRunStatus.SUCCEEDED), isNull());
        verify(aiClient, never()).streamTurn(any(), any(), any(), any());
    }

    @Test
    @DisplayName("unexpected stream error: finishRun FAILED in finally")
    void streamReply_onUnexpectedError_finishRunFailed() {
        Long conversationId = 702L;
        stubConversation(conversationId, "boom please");
        configureTools(false, List.of());

        doThrow(new IllegalStateException("sse broken"))
                .when(aiClient).stream(any(), any(), any(), any());

        assertThatThrownBy(() -> service.streamReply(conversationId, t -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sse broken");

        verify(agentTraceService).startRun(any(StartRunCmd.class));
        verify(agentTraceService).finishRun(eq(activeCtx), eq(AgentRunStatus.FAILED), eq("UNEXPECTED"));
    }

    @Test
    @DisplayName("trace service null: companion stream still succeeds (no NPE)")
    void streamReply_withoutTraceService_stillWorks() {
        ReflectionTestUtils.setField(service, "agentTraceService", null);
        Long conversationId = 703L;
        stubConversation(conversationId, "hello");
        configureTools(false, List.of());

        doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(3);
            onToken.accept("hi");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, t -> { });

        verify(agentTraceService, never()).startRun(any());
        verify(messageMapper).insert(any(CompanionMessage.class));
    }

    @Test
    @DisplayName("startRun returns NOOP: stream still works; finishRun still invoked with NOOP")
    void streamReply_noopContext_behaviorUnchanged() {
        when(agentTraceService.startRun(any())).thenReturn(TraceContext.NOOP);
        Long conversationId = 704L;
        stubConversation(conversationId, "noop path");
        configureTools(false, List.of());

        doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(3);
            onToken.accept("ok");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, t -> { });

        verify(agentTraceService).startRun(any());
        // phase2 step still attempted; AgentTraceService.step no-ops on NOOP
        verify(agentTraceService).step(eq(TraceContext.NOOP), any(StepRecord.class));
        verify(agentTraceService).finishRun(eq(TraceContext.NOOP), eq(AgentRunStatus.SUCCEEDED), isNull());
    }

    @Test
    @DisplayName("trace step failure must not break streamReply")
    void streamReply_traceStepThrows_doesNotBreakStream() {
        doThrow(new RuntimeException("trace db down"))
                .when(agentTraceService).step(any(), any());
        Long conversationId = 705L;
        stubConversation(conversationId, "still answer me");
        configureTools(false, List.of());

        List<String> tokens = new ArrayList<>();
        doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(3);
            onToken.accept("answer");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, tokens::add);

        assertThat(tokens).containsExactly("answer");
        verify(agentTraceService).finishRun(eq(activeCtx), eq(AgentRunStatus.SUCCEEDED), isNull());
    }

    // ── agent_run.model：记录调用时的生效模型（A4 系统配置可热改 wenjin.ai.model）──────

    @Test
    @DisplayName("run records the model in effect now; hot-changed wenjin.ai.model is picked up by the next run")
    void streamReply_recordsCurrentModel_followsHotChange() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.setModel("deepseek-v4-pro");
        ReflectionTestUtils.setField(service, "aiProperties", aiProperties);
        configureTools(false, List.of());

        stubLegacyStream(711L, "第一问");
        service.streamReply(711L, t -> { });
        aiProperties.setModel("glm-5");   // 运行时热改，模拟 A4 系统配置
        stubLegacyStream(712L, "第二问");
        service.streamReply(712L, t -> { });

        ArgumentCaptor<StartRunCmd> startCap = ArgumentCaptor.forClass(StartRunCmd.class);
        verify(agentTraceService, times(2)).startRun(startCap.capture());
        assertThat(startCap.getAllValues()).extracting(StartRunCmd::model)
                .containsExactly("deepseek-v4-pro", "glm-5");
    }

    @Test
    @DisplayName("tools path records the model too")
    void streamReplyWithTools_recordsCurrentModel() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.setModel("deepseek-v4-pro");
        ReflectionTestUtils.setField(service, "aiProperties", aiProperties);
        Long conversationId = 713L;
        stubConversation(conversationId, "查询我的掌握度");
        configureTools(true, List.of(fakeTool("query_mastery", "正在查询…")));
        scriptTurns(turn(null, null, "好的"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, t -> { });

        ArgumentCaptor<StartRunCmd> startCap = ArgumentCaptor.forClass(StartRunCmd.class);
        verify(agentTraceService).startRun(startCap.capture());
        assertThat(startCap.getValue().model()).isEqualTo("deepseek-v4-pro");
    }

    @Test
    @DisplayName("zero-LLM template path (companion-llm-enabled=false) keeps model null: no model is called")
    void streamReply_llmKillSwitchOff_modelStaysNull() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.setModel("deepseek-v4-pro");
        ReflectionTestUtils.setField(service, "aiProperties", aiProperties);
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setCompanionLlmEnabled(false);
        ReflectionTestUtils.setField(service, "featureFlags", flags);
        configureTools(false, List.of());
        Long conversationId = 714L;
        stubConversation(conversationId, "kill switch");
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, t -> { });

        ArgumentCaptor<StartRunCmd> startCap = ArgumentCaptor.forClass(StartRunCmd.class);
        verify(agentTraceService).startRun(startCap.capture());
        assertThat(startCap.getValue().model()).isNull();
        verify(aiClient, never()).stream(any(), any(), any(), any());
    }

    @Test
    @DisplayName("blank / unset model is recorded as null, never as an empty string")
    void streamReply_blankOrMissingModel_recordedAsNull() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.setModel("   ");
        ReflectionTestUtils.setField(service, "aiProperties", aiProperties);
        configureTools(false, List.of());

        stubLegacyStream(715L, "blank model");
        service.streamReply(715L, t -> { });
        ReflectionTestUtils.setField(service, "aiProperties", null);
        stubLegacyStream(716L, "no properties bean");
        service.streamReply(716L, t -> { });

        ArgumentCaptor<StartRunCmd> startCap = ArgumentCaptor.forClass(StartRunCmd.class);
        verify(agentTraceService, times(2)).startRun(startCap.capture());
        assertThat(startCap.getAllValues()).extracting(StartRunCmd::model).containsOnlyNulls();
    }

    // ── llm_round usage / disconnect / interruption ──────────────────────────

    @Test
    @DisplayName("real provider usage goes into llm_round tokens and the run is not marked estimated")
    void streamReplyWithTools_realUsageRecorded() {
        Long conversationId = 720L;
        stubConversation(conversationId, "你好");
        configureTools(true, List.of(fakeTool("query_mastery", "正在查询…")));
        scriptTurns(turn(new CompanionAiClient.Usage(1200, 34, 1024), null, "你好！"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, t -> { });

        ArgumentCaptor<StepRecord> stepCap = ArgumentCaptor.forClass(StepRecord.class);
        verify(agentTraceService).step(eq(activeCtx), stepCap.capture());
        StepRecord round = stepCap.getValue();
        assertThat(round.name()).isEqualTo("llm_round");
        assertThat(round.tokensIn()).isEqualTo(1200);
        assertThat(round.tokensOut()).isEqualTo(34);
        assertThat(activeCtx.tokensEstimated()).isZero();
        verify(agentTraceService).finishRun(eq(activeCtx), eq(AgentRunStatus.SUCCEEDED), isNull());
    }

    @Test
    @DisplayName("client disconnect mid-stream -> run CANCELLED with CLIENT_DISCONNECTED, partial text persisted")
    void streamReplyWithTools_clientDisconnect_cancelledRun() {
        Long conversationId = 721L;
        stubConversation(conversationId, "讲讲二次函数");
        configureTools(true, List.of(fakeTool("query_mastery", "正在查询…")));
        scriptTurns(turn(null, null, "第一段", "第二段"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        List<String> delivered = new ArrayList<>();
        service.streamReply(conversationId, t -> {
            if (!delivered.isEmpty()) {
                throw new IllegalStateException("broken pipe");
            }
            delivered.add(t);
        });

        assertThat(delivered).containsExactly("第一段");
        verify(agentTraceService).finishRun(eq(activeCtx), eq(AgentRunStatus.CANCELLED), eq("CLIENT_DISCONNECTED"));
        ArgumentCaptor<CompanionMessage> saved = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper).insert(saved.capture());
        assertThat(saved.getValue().getContent()).startsWith("第一段");
    }

    @Test
    @DisplayName("LLM failure after text was emitted -> run FAILED (LLM_INTERRUPTED), no degrade")
    void streamReplyWithTools_llmFailsAfterEmission_failedRun() {
        Long conversationId = 722L;
        stubConversation(conversationId, "讲讲二次函数");
        configureTools(true, List.of(fakeTool("query_mastery", "正在查询…")));
        when(aiClient.streamTurn(any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<String> cb = inv.getArgument(3);
            cb.accept("二次函数的图像");
            throw new IllegalStateException("upstream reset");
        });
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, t -> { });

        verify(agentTraceService).finishRun(eq(activeCtx), eq(AgentRunStatus.FAILED), eq("LLM_INTERRUPTED"));
        ArgumentCaptor<StepRecord> stepCap = ArgumentCaptor.forClass(StepRecord.class);
        verify(agentTraceService).step(eq(activeCtx), stepCap.capture());
        assertThat(stepCap.getValue().name()).isEqualTo("llm_round");
        assertThat(stepCap.getValue().status()).isEqualTo(AgentStepStatus.FAILED);
        verify(aiClient, never()).stream(any(), any(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record ScriptedTurn(CompanionAiClient.Usage usage, CompanionAiClient.ToolCall call, List<String> deltas) {
    }

    private static ScriptedTurn turn(CompanionAiClient.Usage usage, CompanionAiClient.ToolCall call, String... deltas) {
        return new ScriptedTurn(usage, call, List.of(deltas));
    }

    private void scriptTurns(ScriptedTurn... turns) {
        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger();
        when(aiClient.streamTurn(any(), any(), any(), any())).thenAnswer(inv -> {
            ScriptedTurn t = turns[Math.min(idx.getAndIncrement(), turns.length - 1)];
            Consumer<String> cb = inv.getArgument(3);
            t.deltas().forEach(cb);
            List<CompanionAiClient.ToolCall> calls = t.call() == null ? List.of() : List.of(t.call());
            return new CompanionAiClient.StreamTurnResult(String.join("", t.deltas()), calls, t.usage(),
                    calls.isEmpty() ? "stop" : "tool_calls");
        });
    }

    /** 旧路径（工具关闭）下一个能正常出流的会话。 */
    private void stubLegacyStream(Long conversationId, String userText) {
        stubConversation(conversationId, userText);
        doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(3);
            onToken.accept("ok");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());
        lenient().when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);
    }

    private void stubConversation(Long conversationId, String userText) {
        CompanionConversation conversation = new CompanionConversation();
        conversation.setId(conversationId);
        conversation.setStudentId(1L);
        conversation.setCourseId(10L);
        conversation.setNodeCode("KT12");
        when(conversationMapper.selectById(conversationId)).thenReturn(conversation);

        CompanionMessage user = new CompanionMessage();
        user.setRole(ROLE_USER);
        user.setContent(userText);
        user.setCreatedAt(LocalDateTime.now());
        when(messageMapper.selectSinceIdAsc(eq(conversationId), any())).thenReturn(List.of(user));

        KgNode node = new KgNode();
        node.setId(12L);
        node.setNodeCode("KT12");
        node.setName("二次函数基础");
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(node));
        when(nodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(node);

        LearningPathVO path = new LearningPathVO();
        LearningPathVO.NodeRef target = new LearningPathVO.NodeRef();
        target.setNodeCode("KT20");
        target.setName("一元二次方程");
        path.setTargetNode(target);
        LearningPathVO.StepVO step = new LearningPathVO.StepVO();
        step.setNodeCode("KT12");
        step.setName("二次函数基础");
        step.setStatus(0);
        path.setSteps(List.of(step));
        when(pathService.getCurrent(1L, 10L)).thenReturn(path);
        when(diagnosticResultService.getResult(1L, 10L)).thenReturn(null);
        when(courseMapper.selectById(10L)).thenReturn(null);
    }

    private void configureTools(boolean enabled, List<CompanionTool> tools) {
        CompanionToolProperties properties = new CompanionToolProperties();
        properties.setEnabled(enabled);
        properties.setMaxRounds(2);
        properties.setMaxToolCalls(3);
        properties.setIntentRoutingEnabled(false);
        ReflectionTestUtils.setField(service, "toolProperties", properties);
        ReflectionTestUtils.setField(service, "companionTools", tools);
        ReflectionTestUtils.setField(service, "toolGovernor", toolGovernor);
    }

    private CompanionTool fakeTool(String name, String description) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", Map.of("type", "object", "properties", Map.of()));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return new CompanionTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Map<String, Object> schema() {
                return schema;
            }

            @Override
            public String execute(ToolContext ctx, com.fasterxml.jackson.databind.JsonNode args) {
                throw new UnsupportedOperationException("not used");
            }
        };
    }

    private MasteryDecayCalculator makeCalculator() {
        MasteryDecayCalculator calculator = new MasteryDecayCalculator();
        ReflectionTestUtils.setField(calculator, "enabled", true);
        ReflectionTestUtils.setField(calculator, "tauDays", 30.0);
        ReflectionTestUtils.setField(calculator, "floor", 25.0);
        ReflectionTestUtils.setField(calculator, "masteredThreshold", 75.0);
        ReflectionTestUtils.setField(calculator, "weakThreshold", 40.0);
        return calculator;
    }
}
