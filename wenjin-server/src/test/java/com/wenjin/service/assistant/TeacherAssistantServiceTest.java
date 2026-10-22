package com.wenjin.service.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AiProperties;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.CompanionMessageVO;
import com.wenjin.dto.InsightCard;
import com.wenjin.dto.TeacherConversationVO;
import com.wenjin.entity.Course;
import com.wenjin.entity.TeacherConversation;
import com.wenjin.entity.TeacherMessage;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.StudentCourseMapper;
import com.wenjin.mapper.TeacherConversationMapper;
import com.wenjin.mapper.TeacherMessageMapper;
import com.wenjin.service.CompanionService.CompanionStreamSink;
import com.wenjin.service.companion.CompanionTool;
import com.wenjin.service.companion.ToolAudience;
import com.wenjin.service.companion.ToolContext;
import com.wenjin.service.companion.ToolExecutionResult;
import com.wenjin.service.companion.ToolGovernor;
import com.wenjin.service.memory.MemoryJobEnqueueService;
import com.wenjin.service.memory.StudentMemoryService;
import com.wenjin.service.trace.AgentTraceService;
import com.wenjin.service.trace.StartRunCmd;
import com.wenjin.service.trace.TraceContext;
import com.wenjin.support.AgentRunStatus;
import com.wenjin.support.ConversationContextBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeacherAssistantServiceTest {

    private static final long TEACHER = 9L;
    private static final long COURSE = 5L;
    private static final long CONV = 77L;

    @Mock TeacherConversationMapper conversationMapper;
    @Mock TeacherMessageMapper messageMapper;
    @Mock CourseMapper courseMapper;
    @Mock StudentCourseMapper studentCourseMapper;
    @Mock CompanionAiClient aiClient;
    @Mock AgentTraceService traceService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FeatureFlagsProperties flags;
    private LimitsProperties limits;
    private AiProperties aiProperties;
    private final List<TeacherMessage> savedMessages = new ArrayList<>();
    private final List<List<Map<String, Object>>> turnSchemas = new ArrayList<>();
    private final List<List<Map<String, Object>>> turnMessages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        flags = new FeatureFlagsProperties();
        limits = new LimitsProperties();
        aiProperties = new AiProperties();
        aiProperties.setEnabled(true);
        aiProperties.setModel("deepseek-test");
        Course course = new Course();
        course.setId(COURSE);
        course.setName("Java 程序设计");
        when(courseMapper.selectById(COURSE)).thenReturn(course);
        when(studentCourseMapper.selectCount(any())).thenReturn(32L);
        when(traceService.startRun(any())).thenReturn(TraceContext.active(501L));
        doAnswer(inv -> {
            TeacherMessage m = inv.getArgument(0);
            m.setId(1000L + savedMessages.size());
            savedMessages.add(m);
            return 1;
        }).when(messageMapper).insert(any(TeacherMessage.class));
    }

    private TeacherAssistantService service(List<CompanionTool> tools) {
        CompanionToolProperties toolProperties = new CompanionToolProperties();
        toolProperties.setEnabled(true);
        ToolGovernor governor = new ToolGovernor(tools, objectMapper, toolProperties, limits);
        Clock clock = Clock.fixed(Instant.parse("2026-10-22T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        return new TeacherAssistantService(conversationMapper, messageMapper, courseMapper, studentCourseMapper,
                aiClient, governor, new ConversationContextBuilder(limits), flags, limits, clock,
                tools, traceService, aiProperties);
    }

    private TeacherConversation ownedConversation() {
        TeacherConversation c = new TeacherConversation();
        c.setId(CONV);
        c.setTeacherId(TEACHER);
        c.setCourseId(COURSE);
        c.setTitle("t");
        return c;
    }

    private static TeacherMessage msg(long id, int role, String content) {
        TeacherMessage m = new TeacherMessage();
        m.setId(id);
        m.setConversationId(CONV);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    private void scriptTurns(List<CompanionAiClient.StreamTurnResult> results, List<List<String>> deltas) {
        int[] i = {0};
        when(aiClient.streamTurn(anyList(), anyList(), any(), any())).thenAnswer(inv -> {
            turnMessages.add(new ArrayList<>(inv.getArgument(0)));
            turnSchemas.add(new ArrayList<>(inv.getArgument(1)));
            Consumer<String> onDelta = inv.getArgument(3);
            int k = i[0]++;
            for (String d : deltas.get(k)) {
                onDelta.accept(d);
            }
            return results.get(k);
        });
    }

    private static CompanionAiClient.StreamTurnResult text(String content) {
        return new CompanionAiClient.StreamTurnResult(content, List.of(), null, "stop");
    }

    // ── 会话 ──

    @Test
    @DisplayName("startTurn：新会话归属当前老师 + 课程，标题取首问截断，落用户消息")
    void startTurn_createsOwnedConversation() {
        doAnswer(inv -> {
            TeacherConversation c = inv.getArgument(0);
            c.setId(CONV);
            return 1;
        }).when(conversationMapper).insert(any(TeacherConversation.class));

        Long id = service(List.of()).startTurn(TEACHER, COURSE, null, "这周哪些学生需要关注，为什么？请按优先级列出来");

        assertThat(id).isEqualTo(CONV);
        ArgumentCaptor<TeacherConversation> captor = ArgumentCaptor.forClass(TeacherConversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertThat(captor.getValue().getTeacherId()).isEqualTo(TEACHER);
        assertThat(captor.getValue().getCourseId()).isEqualTo(COURSE);
        assertThat(captor.getValue().getTitle()).startsWith("这周哪些学生需要关注").hasSizeLessThanOrEqualTo(23);
        assertThat(savedMessages).singleElement().satisfies(m -> {
            assertThat(m.getRole()).isEqualTo(1);
            assertThat(m.getConversationId()).isEqualTo(CONV);
        });
    }

    @Test
    @DisplayName("startTurn：他人会话 / 课程不符 → 404，不落消息")
    void startTurn_foreignConversation404() {
        TeacherConversation foreign = ownedConversation();
        foreign.setTeacherId(123L);
        when(conversationMapper.selectById(CONV)).thenReturn(foreign);
        TeacherAssistantService svc = service(List.of());

        assertThatThrownBy(() -> svc.startTurn(TEACHER, COURSE, CONV, "hi"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(ResultCode.NOT_FOUND.getCode());

        TeacherConversation otherCourse = ownedConversation();
        otherCourse.setCourseId(6L);
        when(conversationMapper.selectById(CONV)).thenReturn(otherCourse);
        assertThatThrownBy(() -> svc.startTurn(TEACHER, COURSE, CONV, "hi"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(ResultCode.NOT_FOUND.getCode());
        assertThat(savedMessages).isEmpty();
    }

    @Test
    @DisplayName("startTurn：超出 max-input-chars → 413")
    void startTurn_tooLong413() {
        limits.getTeacherAssistant().setMaxInputChars(5);
        assertThatThrownBy(() -> service(List.of()).startTurn(TEACHER, COURSE, null, "123456"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.PAYLOAD_TOO_LARGE.getCode());
    }

    @Test
    @DisplayName("开关关闭 → assertEnabled 404")
    void flagOff_404() {
        flags.setTeacherAssistantEnabled(false);
        assertThatThrownBy(() -> service(List.of()).assertEnabled())
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(ResultCode.NOT_FOUND.getCode());
        flags.setTeacherAssistantEnabled(true);
        service(List.of()).assertEnabled();
    }

    @Test
    @DisplayName("getMessages / delete：他人会话 404；ADMIN 也只能看自己的（按 teacher_id 判定）")
    void messages_foreign404_deleteOwn() {
        TeacherConversation foreign = ownedConversation();
        foreign.setTeacherId(1L);
        when(conversationMapper.selectById(CONV)).thenReturn(foreign);
        TeacherAssistantService svc = service(List.of());
        assertThatThrownBy(() -> svc.getMessages(TEACHER, CONV, null, null))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(ResultCode.NOT_FOUND.getCode());
        assertThatThrownBy(() -> svc.deleteConversation(TEACHER, CONV))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(ResultCode.NOT_FOUND.getCode());
        verify(messageMapper, never()).deleteByConversation(anyLong());

        when(conversationMapper.selectById(CONV)).thenReturn(ownedConversation());
        when(messageMapper.selectKeysetByConversation(eq(CONV), isNull(), anyInt()))
                .thenReturn(List.of(msg(2, 2, "答"), msg(1, 1, "问")));
        List<CompanionMessageVO> messages = svc.getMessages(TEACHER, CONV, null, null);
        assertThat(messages).extracting(CompanionMessageVO::getRole).containsExactly("user", "ai");
        svc.deleteConversation(TEACHER, CONV);
        verify(messageMapper).deleteByConversation(CONV);
        verify(conversationMapper).deleteById(CONV);
    }

    @Test
    @DisplayName("listConversations：按本人 × 课程 keyset 查询")
    void listConversations_ownOnly() {
        when(conversationMapper.selectKeysetByTeacherCourse(eq(TEACHER), eq(COURSE), isNull(), anyInt()))
                .thenReturn(List.of(ownedConversation()));
        List<TeacherConversationVO> list = service(List.of()).listConversations(TEACHER, COURSE, null, null);
        assertThat(list).singleElement().satisfies(vo -> {
            assertThat(vo.getId()).isEqualTo(CONV);
            assertThat(vo.getCourseId()).isEqualTo(COURSE);
        });
    }

    // ── 流式回答 ──

    @Test
    @DisplayName("零工具：纯流式回答（schemas 为空）、逐 token 下发、落库；trace kind=TEACHER_CHAT 且 student_id 列存老师 id")
    void streamReply_zeroTools_plainAnswer() {
        when(conversationMapper.selectById(CONV)).thenReturn(ownedConversation());
        when(messageMapper.selectSinceIdAsc(CONV, null)).thenReturn(List.of(msg(1, 1, "第三章哪个知识点最薄弱？")));
        scriptTurns(List.of(text("暂时无法查询。")), List.of(List.of("暂时", "无法查询。")));
        List<String> tokens = new ArrayList<>();

        service(List.of()).streamReply(TEACHER, CONV, ToolContext.forTeacher(TEACHER, COURSE), sink(tokens, null));

        assertThat(tokens).containsExactly("暂时", "无法查询。");
        assertThat(turnSchemas).singleElement().satisfies(s -> assertThat(s).isEmpty());
        assertThat(savedMessages).singleElement().satisfies(m -> {
            assertThat(m.getRole()).isEqualTo(2);
            assertThat(m.getContent()).isEqualTo("暂时无法查询。");
        });
        ArgumentCaptor<StartRunCmd> cmd = ArgumentCaptor.forClass(StartRunCmd.class);
        verify(traceService).startRun(cmd.capture());
        assertThat(cmd.getValue().kind()).isEqualTo(TeacherAssistantService.TRACE_KIND);
        assertThat(cmd.getValue().kind()).isEqualTo("TEACHER_CHAT");
        assertThat(cmd.getValue().studentId()).isEqualTo(TEACHER);
        assertThat(cmd.getValue().courseId()).isEqualTo(COURSE);
        assertThat(cmd.getValue().conversationId()).isEqualTo(CONV);
        assertThat(cmd.getValue().model()).isEqualTo("deepseek-test");
        verify(traceService).finishRun(any(), eq(AgentRunStatus.SUCCEEDED), isNull());
    }

    @Test
    @DisplayName("系统提示：静态段在前（角色、依据、隐私红线、表格卡片），动态段含课程名 / 选课人数 / 日期；无记忆与检索槽")
    void streamReply_promptAndNoMemoryOrRetrievalSlots() {
        when(conversationMapper.selectById(CONV)).thenReturn(ownedConversation());
        when(messageMapper.selectSinceIdAsc(CONV, null)).thenReturn(List.of(msg(1, 1, "概况")));
        scriptTurns(List.of(text("ok")), List.of(List.of("ok")));

        service(List.of()).streamReply(TEACHER, CONV, ToolContext.forTeacher(TEACHER, COURSE), sink(new ArrayList<>(), null));

        List<Map<String, Object>> messages = turnMessages.get(0);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).containsEntry("role", "system");
        assertThat(messages.get(1)).containsEntry("role", "user").containsEntry("content", "概况");
        String prompt = (String) messages.get(0).get("content");
        assertThat(prompt).contains("课程助教", "学生长期记忆", "伴侣", "对话", "表格", "数据不足");
        assertThat(prompt).contains("《Java 程序设计》", "32", "2026-10-22");
        assertThat(prompt.indexOf("## 隐私")).isLessThan(prompt.indexOf("## 当前课程"));
        assertThat(prompt.indexOf("《Java 程序设计》", prompt.indexOf("## 当前课程"))).isPositive();
    }

    @Test
    @DisplayName("只提供 TEACHER 工具；学生工具不出现在 schema 中；工具收到老师 ToolContext；insight_card 下发")
    void streamReply_offersTeacherToolsOnly() {
        AtomicReference<ToolContext> seenCtx = new AtomicReference<>();
        InsightCard card = new InsightCard("需关注学生", List.of("学生"), List.of(List.of("张三")), null);
        CompanionTool teacherTool = tool("list_students_needing_attention", ToolAudience.TEACHER, seenCtx, card);
        CompanionTool studentTool = tool("query_mastery", ToolAudience.STUDENT, new AtomicReference<>(), null);
        when(conversationMapper.selectById(CONV)).thenReturn(ownedConversation());
        when(messageMapper.selectSinceIdAsc(CONV, null)).thenReturn(List.of(msg(1, 1, "谁需要关注")));
        scriptTurns(List.of(
                new CompanionAiClient.StreamTurnResult("", List.of(
                        new CompanionAiClient.ToolCall("c1", "list_students_needing_attention", "{}")), null, "tool_calls"),
                text("张三需要关注。")), List.of(List.of(), List.of("张三需要关注。")));
        List<InsightCard> cards = new ArrayList<>();

        service(List.of(studentTool, teacherTool)).streamReply(TEACHER, CONV,
                ToolContext.forTeacher(TEACHER, COURSE), sink(new ArrayList<>(), cards));

        assertThat(turnSchemas.get(0)).hasSize(1);
        assertThat(turnSchemas.get(0).toString()).contains("list_students_needing_attention").doesNotContain("query_mastery");
        assertThat(seenCtx.get().isTeacher()).isTrue();
        assertThat(seenCtx.get().courseId()).isEqualTo(COURSE);
        assertThat(seenCtx.get().actorUserId()).isEqualTo(TEACHER);
        assertThat(cards).containsExactly(card);
        String prompt = (String) turnMessages.get(0).get(0).get("content");
        assertThat(prompt).contains("list_students_needing_attention");
    }

    @Test
    @DisplayName("摘要折叠写 teacher_conversation；助教不依赖任何记忆 / 蒸馏组件")
    void streamReply_foldsSummaryWithoutMemoryDistillation() throws Exception {
        limits.getCompanion().setMaxRecentMessages(2);
        when(conversationMapper.selectById(CONV)).thenReturn(ownedConversation());
        when(messageMapper.selectSinceIdAsc(CONV, null)).thenReturn(List.of(
                msg(1, 1, "问一"), msg(2, 2, "答一"), msg(3, 1, "问二"), msg(4, 2, "答二"), msg(5, 1, "问三")));
        when(conversationMapper.updateSummaryGuarded(eq(CONV), anyString(), eq(3L))).thenReturn(1);
        scriptTurns(List.of(text("ok")), List.of(List.of("ok")));

        service(List.of()).streamReply(TEACHER, CONV, ToolContext.forTeacher(TEACHER, COURSE), sink(new ArrayList<>(), null));

        verify(conversationMapper).updateSummaryGuarded(eq(CONV), anyString(), eq(3L));
        for (Field f : TeacherAssistantService.class.getDeclaredFields()) {
            assertThat(f.getType()).isNotIn(MemoryJobEnqueueService.class, StudentMemoryService.class);
        }
    }

    @Test
    @DisplayName("LLM 关（companion-llm-enabled=false 或 wenjin.ai.enabled=false）→ 模板话术 + 指向学情看板，不调模型，run DEGRADED")
    void streamReply_llmDisabled_degrades() {
        flags.setCompanionLlmEnabled(false);
        when(conversationMapper.selectById(CONV)).thenReturn(ownedConversation());
        when(messageMapper.selectSinceIdAsc(CONV, null)).thenReturn(List.of(msg(1, 1, "概况")));
        List<String> tokens = new ArrayList<>();

        service(List.of()).streamReply(TEACHER, CONV, ToolContext.forTeacher(TEACHER, COURSE), sink(tokens, null));

        assertThat(String.join("", tokens)).isEqualTo(TeacherAssistantService.DEGRADED_REPLY).contains("学情看板");
        assertThat(savedMessages).singleElement().extracting(TeacherMessage::getContent)
                .isEqualTo(TeacherAssistantService.DEGRADED_REPLY);
        verify(aiClient, never()).streamTurn(any(), any(), any(), any());
        verify(traceService).finishRun(any(), eq(AgentRunStatus.DEGRADED), isNull());

        flags.setCompanionLlmEnabled(true);
        aiProperties.setEnabled(false);
        tokens.clear();
        service(List.of()).streamReply(TEACHER, CONV, ToolContext.forTeacher(TEACHER, COURSE), sink(tokens, null));
        assertThat(String.join("", tokens)).isEqualTo(TeacherAssistantService.DEGRADED_REPLY);
        verify(aiClient, never()).streamTurn(any(), any(), any(), any());
    }

    @Test
    @DisplayName("LLM 调用失败（尚未下发文字）→ 降级模板，run DEGRADED")
    void streamReply_llmFailure_degrades() {
        when(conversationMapper.selectById(CONV)).thenReturn(ownedConversation());
        when(messageMapper.selectSinceIdAsc(CONV, null)).thenReturn(List.of(msg(1, 1, "概况")));
        when(aiClient.streamTurn(anyList(), anyList(), any(), any())).thenThrow(new RuntimeException("boom"));
        List<String> tokens = new ArrayList<>();

        service(List.of()).streamReply(TEACHER, CONV, ToolContext.forTeacher(TEACHER, COURSE), sink(tokens, null));

        assertThat(String.join("", tokens)).isEqualTo(TeacherAssistantService.DEGRADED_REPLY);
        verify(traceService).finishRun(any(), eq(AgentRunStatus.DEGRADED), isNull());
    }

    @Test
    @DisplayName("streamReply：他人会话 → 404，不调模型")
    void streamReply_foreign404() {
        TeacherConversation foreign = ownedConversation();
        foreign.setTeacherId(1L);
        when(conversationMapper.selectById(CONV)).thenReturn(foreign);
        assertThatThrownBy(() -> service(List.of()).streamReply(TEACHER, CONV,
                ToolContext.forTeacher(TEACHER, COURSE), sink(new ArrayList<>(), null)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(ResultCode.NOT_FOUND.getCode());
        verify(aiClient, never()).streamTurn(any(), any(), any(), any());
    }

    @Test
    @DisplayName("系统提示词：无工具时明确不得编造数据")
    void systemPrompt_withoutTools_forbidsFabrication() {
        String prompt = TeacherAssistantService.buildSystemPrompt("Java", 0, java.time.LocalDate.of(2026, 10, 22), List.of());
        assertThat(prompt).contains("当前没有可用的数据查询工具");
        assertThat(prompt).contains("0 名");
    }

    // ── helpers ──

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private static CompanionStreamSink sink(List<String> tokens, List<InsightCard> cards) {
        return new CompanionStreamSink() {
            @Override
            public void token(String token) {
                tokens.add(token);
            }

            @Override
            public void insightCard(InsightCard card) {
                if (cards != null) {
                    cards.add(card);
                }
            }
        };
    }

    private static CompanionTool tool(String name, ToolAudience audience, AtomicReference<ToolContext> seen,
                                      InsightCard card) {
        return new CompanionTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "function", "function",
                        Map.of("name", name, "description", "label " + name,
                                "parameters", Map.of("type", "object", "properties", Map.of())));
            }

            @Override
            public ToolAudience audience() {
                return audience;
            }

            @Override
            public boolean readCacheable() {
                return true;
            }

            @Override
            public String execute(ToolContext ctx, JsonNode args) {
                seen.set(ctx);
                return "{\"students\":[\"张三\"]}";
            }

            @Override
            public ToolExecutionResult executeForGovernor(String id, ToolContext ctx, JsonNode args) {
                return new ToolExecutionResult(id, name, true, execute(ctx, args), null, false, false, null, card);
            }
        };
    }
}
