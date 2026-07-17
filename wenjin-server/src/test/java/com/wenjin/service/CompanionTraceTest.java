package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.CompanionToolProperties;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 7: Companion phase1 / phase2 / TOOL_CALL 埋点 + run 边界（try/finally finishRun）。
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
    @DisplayName("tools path: startRun + phase1 + TOOL_CALL + phase2 + finishRun SUCCEEDED")
    void streamReplyWithTools_recordsFullTrace() {
        Long conversationId = 700L;
        stubConversation(conversationId, "查询我的掌握度");
        configureTools(true, List.of(fakeTool("query_mastery", "正在查询…")));

        when(aiClient.completeWithTools(any(), any())).thenReturn(
                new CompanionAiClient.ToolCalls(List.of(
                        new CompanionAiClient.ToolCall("call-1", "query_mastery", "{\"nodeCode\":\"KT12\"}"))),
                new CompanionAiClient.FinalText("done"));
        when(toolGovernor.execute(any(), any(), any())).thenReturn(
                new ToolExecutionResult("call-1", "query_mastery", true, "{\"items\":[]}"));
        doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(1);
            onToken.accept("根据掌握度来看…");
            return null;
        }).when(aiClient).streamMessages(any(), any());
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
        verify(agentTraceService, times(4)).step(eq(activeCtx), stepCap.capture());
        List<StepRecord> steps = stepCap.getAllValues();

        // phase1 decision (tool_calls), TOOL_CALL, phase1 final_text stop, phase2 stream
        List<String> names = steps.stream().map(StepRecord::name).collect(Collectors.toList());
        assertThat(names).contains("phase1", "query_mastery", "phase2");
        assertThat(names.stream().filter("phase1"::equals).count()).isEqualTo(2);

        StepRecord toolStep = steps.stream()
                .filter(s -> AgentStepType.TOOL_CALL.equals(s.stepType()))
                .findFirst()
                .orElseThrow();
        assertThat(toolStep.name()).isEqualTo("query_mastery");
        assertThat(toolStep.status()).isEqualTo(AgentStepStatus.OK);
        assertThat(toolStep.inputJson()).contains("KT12");
        assertThat(toolStep.outputJson()).contains("items");

        StepRecord phase2 = steps.stream()
                .filter(s -> "phase2".equals(s.name()))
                .findFirst()
                .orElseThrow();
        assertThat(phase2.stepType()).isEqualTo(AgentStepType.LLM_CALL);
        assertThat(phase2.status()).isEqualTo(AgentStepStatus.OK);
        assertThat(phase2.outputJson()).contains("根据掌握度");
        // chars/2 estimate when no usage
        assertThat(phase2.tokensOut()).isEqualTo("根据掌握度来看…".length() / 2);
        assertThat(activeCtx.tokensEstimated()).isEqualTo(1);

        verify(agentTraceService, times(1)).finishRun(eq(activeCtx), eq(AgentRunStatus.SUCCEEDED), isNull());
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
        verify(aiClient, never()).completeWithTools(any(), any());
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

    // ── helpers ──────────────────────────────────────────────────────────────

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
