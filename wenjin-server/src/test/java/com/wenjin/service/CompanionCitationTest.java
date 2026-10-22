package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.ai.CompanionAiClient;
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
import com.wenjin.service.companion.ToolGovernor;
import com.wenjin.service.rag.InMemoryFulltextRetriever;
import com.wenjin.service.rag.RetrievalService;
import com.wenjin.service.trace.AgentTraceService;
import com.wenjin.service.trace.StartRunCmd;
import com.wenjin.service.trace.StepRecord;
import com.wenjin.service.trace.TraceContext;
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
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R4 引用校验贯通：prompt 规则、可引用集合（注入 ∪ search_materials）、持久化改写、citations 事件、
 * citation_check trace、rag-enabled=false kill switch，覆盖工具路径与 legacy 路径。
 */
@ExtendWith(MockitoExtension.class)
class CompanionCitationTest {

    private static final int ROLE_USER = 1;
    private static final String USER_TEXT = "二次函数的图像是什么";

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
                conversationMapper, messageMapper, nodeMapper, masteryMapper, courseMapper,
                pathService, diagnosticResultService, aiClient, makeCalculator(),
                courseService, new ConversationContextBuilder(limits), limits);
        ReflectionTestUtils.setField(service, "agentTraceService", agentTraceService);
        activeCtx = TraceContext.active(77L);
        lenient().when(agentTraceService.startRun(any(StartRunCmd.class))).thenReturn(activeCtx);
        lenient().when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);
        stubConversation(900L);
    }

    // ── prompt ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tool prompt: static 资料引用 section sits before the whitelist and carries all three rules")
    void toolPromptHasCitationRules() {
        String prompt = CompanionServiceImpl.buildSystemPrompt("软件工程", List.of("用例图"), List.of(),
                null, null, null, null, null);
        int rules = prompt.indexOf("## 资料引用");
        assertThat(rules).isPositive();
        assertThat(rules).isLessThan(prompt.indexOf("## 课程知识点范围"));
        String section = prompt.substring(rules, prompt.indexOf("## 课程知识点范围"));
        assertThat(section).contains("资料 #<编号>");
        assertThat(section).contains("search_materials 返回的 chunkId");
        assertThat(section).contains("课程资料未覆盖");
        assertThat(section).contains("不得编造出处");
    }

    @Test
    @DisplayName("legacy prompt carries the citation rules too")
    void legacyPathPromptHasCitationRules() {
        configureTools(false, List.of());
        ArgumentCaptor<String> promptCap = ArgumentCaptor.forClass(String.class);
        doAnswer(inv -> {
            Consumer<String> cb = inv.getArgument(3);
            cb.accept("好的");
            return null;
        }).when(aiClient).stream(promptCap.capture(), any(), any(), any());

        service.streamReply(900L, new RecordingSink());

        assertThat(promptCap.getValue()).contains("## 资料引用").contains("课程资料未覆盖");
    }

    // ── tools path ────────────────────────────────────────────────────────

    @Test
    @DisplayName("citable = injected ∪ search_materials; invalid rewritten in persisted text; citations event last")
    void toolsPath_mergesInjectedAndSearchedIds() throws Exception {
        injectChunk(501L);
        configureTools(true, List.of(fakeTool("search_materials")));
        scriptTurns(
                searchCall("c1"),
                text("抛物线", " 资料 #501，", "顶点见资料 #777，", "别的说法资料 #999。"));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(new ToolExecutionResult(
                "c1", "search_materials", true,
                "{\"items\":[{\"chunkId\":777,\"snippet\":\"s\",\"sourceTitle\":\"t\"}],\"count\":1,\"scopeWidened\":false}"));

        RecordingSink sink = new RecordingSink();
        service.streamReply(900L, sink);

        // streamed text is untouched (already on the wire) ...
        assertThat(String.join("", sink.tokens)).contains("资料 #999");
        // ... persisted text has the invalid reference replaced
        assertThat(persistedReply()).isEqualTo("抛物线 资料 #501，顶点见资料 #777，别的说法（引用无效）。");
        assertThat(sink.citations).containsExactly("valid=[501, 777] invalid=[999]");
        // ordering: citations after every token / tool event
        assertThat(sink.events.get(sink.events.size() - 1)).startsWith("citations|");
        assertThat(sink.events.stream().filter(e -> e.startsWith("citations|")).count()).isEqualTo(1);

        StepRecord step = citationStep();
        assertThat(step.name()).isEqualTo("citation_check");
        assertThat(step.outputJson()).isEqualTo("{\"valid\":[501,777],\"invalid\":[999]}");
    }

    @Test
    @DisplayName("tools path: hallucinated id with nothing injected or searched is invalid")
    void toolsPath_noCitableSet_everythingInvalid() {
        configureTools(true, List.of(fakeTool("query_mastery")));
        scriptTurns(text("编造的结论 资料 #12。"));

        RecordingSink sink = new RecordingSink();
        service.streamReply(900L, sink);

        assertThat(persistedReply()).isEqualTo("编造的结论 （引用无效）。");
        assertThat(sink.citations).containsExactly("valid=[] invalid=[12]");
    }

    @Test
    @DisplayName("tools path: no citation in the answer -> no event, no step, text untouched")
    void toolsPath_noCitation_noEventNoStep() {
        injectChunk(501L);
        configureTools(true, List.of(fakeTool("query_mastery")));
        scriptTurns(text("课程资料未覆盖这个问题。"));

        RecordingSink sink = new RecordingSink();
        service.streamReply(900L, sink);

        assertThat(sink.citations).isEmpty();
        assertThat(persistedReply()).isEqualTo("课程资料未覆盖这个问题。");
        verifyNoCitationStep();
    }

    @Test
    @DisplayName("kill switch: rag-enabled=false -> no event, text not rewritten, no citation step")
    void killSwitch_ragDisabled_doesNothing() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setRagEnabled(false);
        ReflectionTestUtils.setField(service, "featureFlags", flags);
        injectChunk(501L);
        configureTools(true, List.of(fakeTool("query_mastery")));
        scriptTurns(text("结论 资料 #999。"));

        RecordingSink sink = new RecordingSink();
        service.streamReply(900L, sink);

        assertThat(sink.citations).isEmpty();
        assertThat(persistedReply()).isEqualTo("结论 资料 #999。");
        verifyNoCitationStep();
    }

    @Test
    @DisplayName("LLM interrupted after text: persisted partial text is still neutralised, no event")
    void interrupted_stillNeutralisesInvalid() {
        configureTools(true, List.of(fakeTool("query_mastery")));
        when(aiClient.streamTurn(any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<String> cb = inv.getArgument(3);
            cb.accept("先说 资料 #404");
            throw new IllegalStateException("upstream reset");
        });

        RecordingSink sink = new RecordingSink();
        service.streamReply(900L, sink);

        assertThat(persistedReply()).startsWith("先说 （引用无效）").contains("回答中断");
        assertThat(sink.citations).isEmpty();
    }

    // ── legacy path ───────────────────────────────────────────────────────

    @Test
    @DisplayName("legacy path: injected ids are citable, others rewritten, event + step emitted")
    void legacyPath_validatesAgainstInjected() {
        injectChunk(501L);
        configureTools(false, List.of());
        doAnswer(inv -> {
            Consumer<String> cb = inv.getArgument(3);
            cb.accept("见资料 #501 ");
            cb.accept("和资料 #9");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());

        RecordingSink sink = new RecordingSink();
        service.streamReply(900L, sink);

        assertThat(persistedReply()).isEqualTo("见资料 #501 和（引用无效）");
        assertThat(sink.citations).containsExactly("valid=[501] invalid=[9]");
        assertThat(citationStep().outputJson()).isEqualTo("{\"valid\":[501],\"invalid\":[9]}");
    }

    @Test
    @DisplayName("legacy path + kill switch: untouched")
    void legacyPath_killSwitch() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setRagEnabled(false);
        ReflectionTestUtils.setField(service, "featureFlags", flags);
        configureTools(false, List.of());
        doAnswer(inv -> {
            Consumer<String> cb = inv.getArgument(3);
            cb.accept("见资料 #9");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());

        RecordingSink sink = new RecordingSink();
        service.streamReply(900L, sink);

        assertThat(persistedReply()).isEqualTo("见资料 #9");
        assertThat(sink.citations).isEmpty();
        verifyNoCitationStep();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void injectChunk(long chunkId) {
        InMemoryFulltextRetriever retriever = new InMemoryFulltextRetriever()
                .add(new InMemoryFulltextRetriever.IndexedChunk(
                        10L, chunkId, "二次函数的图像是抛物线", "讲义", null, Set.of("KT12")));
        ReflectionTestUtils.setField(service, "retrievalService", new RetrievalService(retriever));
    }

    private StepRecord citationStep() {
        ArgumentCaptor<StepRecord> cap = ArgumentCaptor.forClass(StepRecord.class);
        verify(agentTraceService, atLeastOnce()).step(eq(activeCtx), cap.capture());
        List<StepRecord> hits = cap.getAllValues().stream()
                .filter(s -> AgentStepType.CITATION.equals(s.stepType()))
                .toList();
        assertThat(hits).hasSize(1);
        return hits.get(0);
    }

    private void verifyNoCitationStep() {
        ArgumentCaptor<StepRecord> cap = ArgumentCaptor.forClass(StepRecord.class);
        verify(agentTraceService, atLeastOnce()).step(eq(activeCtx), cap.capture());
        assertThat(cap.getAllValues()).noneMatch(s -> AgentStepType.CITATION.equals(s.stepType()));
    }

    private String persistedReply() {
        ArgumentCaptor<CompanionMessage> captor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper).insert(captor.capture());
        return captor.getValue().getContent();
    }

    private record Scripted(List<CompanionAiClient.ToolCall> calls, List<String> deltas) {
    }

    private static Scripted text(String... deltas) {
        return new Scripted(List.of(), List.of(deltas));
    }

    private static Scripted searchCall(String id) {
        return new Scripted(List.of(new CompanionAiClient.ToolCall(id, "search_materials", "{\"query\":\"二次函数\"}")),
                List.of());
    }

    private void scriptTurns(Scripted... turns) {
        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger();
        when(aiClient.streamTurn(any(), any(), any(), any())).thenAnswer(inv -> {
            Scripted t = turns[Math.min(idx.getAndIncrement(), turns.length - 1)];
            Consumer<String> cb = inv.getArgument(3);
            t.deltas().forEach(cb);
            return new CompanionAiClient.StreamTurnResult(String.join("", t.deltas()), t.calls(), null,
                    t.calls().isEmpty() ? "stop" : "tool_calls");
        });
    }

    private void stubConversation(Long conversationId) {
        CompanionConversation conversation = new CompanionConversation();
        conversation.setId(conversationId);
        conversation.setStudentId(1L);
        conversation.setCourseId(10L);
        conversation.setNodeCode("KT12");
        lenient().when(conversationMapper.selectById(conversationId)).thenReturn(conversation);

        CompanionMessage user = new CompanionMessage();
        user.setRole(ROLE_USER);
        user.setContent(USER_TEXT);
        user.setCreatedAt(LocalDateTime.now());
        lenient().when(messageMapper.selectSinceIdAsc(eq(conversationId), any())).thenReturn(List.of(user));

        KgNode node = new KgNode();
        node.setId(12L);
        node.setNodeCode("KT12");
        node.setName("二次函数基础");
        lenient().when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(node));
        lenient().when(nodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(node);

        LearningPathVO path = new LearningPathVO();
        lenient().when(pathService.getCurrent(1L, 10L)).thenReturn(path);
        lenient().when(diagnosticResultService.getResult(1L, 10L)).thenReturn(null);
        lenient().when(courseMapper.selectById(10L)).thenReturn(null);
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

    private CompanionTool fakeTool(String name) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", name);
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

    private static final class RecordingSink extends CompanionService.CompanionStreamSink {
        private final List<String> tokens = new ArrayList<>();
        private final List<String> citations = new ArrayList<>();
        private final List<String> events = new ArrayList<>();

        @Override
        public void token(String token) {
            tokens.add(token);
            events.add("token|" + token);
        }

        @Override
        public void toolStart(String name, String label) {
            events.add("tool_start|" + name);
        }

        @Override
        public void toolResult(String name, boolean ok) {
            events.add("tool_result|" + name);
        }

        @Override
        public void citations(List<Long> valid, List<Long> invalid) {
            String s = "valid=" + valid + " invalid=" + invalid;
            citations.add(s);
            events.add("citations|" + s);
        }
    }
}
