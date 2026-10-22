package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.CompanionChatRequest;
import com.wenjin.service.memory.StudentMemoryService;
import com.wenjin.dto.DiagnosticResultVO;
import com.wenjin.dto.LearningPathVO;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.dto.ProbeStartVO;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.entity.CompanionMessage;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
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
import com.wenjin.support.ConversationContextBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanionServiceImplTest {

    @Mock
    private CompanionConversationMapper conversationMapper;

    @Mock
    private CompanionMessageMapper messageMapper;

    @Mock
    private KgNodeMapper nodeMapper;

    @Mock
    private StudentMasteryMapper masteryMapper;

    @Mock
    private CourseMapper courseMapper;

    @Mock
    private PathService pathService;

    @Mock
    private DiagnosticResultService diagnosticResultService;

    @Mock
    private CompanionAiClient aiClient;

    @Mock
    private ToolGovernor toolGovernor;

    @Mock
    private CourseService courseService;

    @Mock
    private StudentMemoryService studentMemoryService;

    private CompanionServiceImpl service;
    private CompanionChatRequest req;

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

        req = new CompanionChatRequest();
        req.setStudentId(1L);
        req.setCourseId(10L);
        req.setMessage("什么是二次函数？");

        configureTools(false, 2, 3, List.of());
    }

    @Test
    void startTurnCreatesConversation() {
        req.setConversationId(null);
        req.setNodeCode("KT12");

        KgNode node = new KgNode();
        node.setNodeCode("KT12");
        node.setName("二次函数基础");
        when(nodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(node);

        doAnswer(invocation -> {
            CompanionConversation conversation = invocation.getArgument(0);
            conversation.setId(100L);
            return 1;
        }).when(conversationMapper).insert(any(CompanionConversation.class));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        Long conversationId = service.startTurn(req);

        assertEquals(100L, conversationId);

        ArgumentCaptor<CompanionConversation> conversationCaptor = ArgumentCaptor.forClass(CompanionConversation.class);
        verify(conversationMapper).insert(conversationCaptor.capture());
        CompanionConversation savedConversation = conversationCaptor.getValue();
        assertEquals(1L, savedConversation.getStudentId());
        assertEquals(10L, savedConversation.getCourseId());
        assertEquals("KT12", savedConversation.getNodeCode());
        assertThat(savedConversation.getTitle()).contains("二次函数基础");
        assertThat(savedConversation.getTitle()).contains("什么是二次函数");

        ArgumentCaptor<CompanionMessage> messageCaptor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        CompanionMessage savedMessage = messageCaptor.getValue();
        assertEquals(100L, savedMessage.getConversationId());
        assertEquals(1, savedMessage.getRole());
        assertEquals("什么是二次函数？", savedMessage.getContent());
    }

    @Test
    void startTurnReusesConversation() {
        req.setConversationId(200L);
        req.setNodeCode("KT99");

        CompanionConversation existingConversation = new CompanionConversation();
        existingConversation.setId(200L);
        existingConversation.setStudentId(1L);
        existingConversation.setCourseId(10L);
        existingConversation.setNodeCode("KT12");
        when(conversationMapper.selectById(200L)).thenReturn(existingConversation);
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);
        when(conversationMapper.updateById(any(CompanionConversation.class))).thenReturn(1);

        Long conversationId = service.startTurn(req);

        assertEquals(200L, conversationId);
        verify(conversationMapper, never()).insert(any(CompanionConversation.class));
        ArgumentCaptor<CompanionConversation> conversationCaptor = ArgumentCaptor.forClass(CompanionConversation.class);
        verify(conversationMapper).updateById(conversationCaptor.capture());
        assertEquals("KT99", conversationCaptor.getValue().getNodeCode());

        ArgumentCaptor<CompanionMessage> messageCaptor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        CompanionMessage savedMessage = messageCaptor.getValue();
        assertEquals(200L, savedMessage.getConversationId());
        assertEquals(1, savedMessage.getRole());
    }

    @Test
    void startTurnReusesConversationKeepsExistingNodeCodeWhenRequestOmitsIt() {
        req.setConversationId(203L);
        req.setNodeCode(null);

        CompanionConversation existingConversation = new CompanionConversation();
        existingConversation.setId(203L);
        existingConversation.setStudentId(1L);
        existingConversation.setCourseId(10L);
        existingConversation.setNodeCode("KT12");
        when(conversationMapper.selectById(203L)).thenReturn(existingConversation);
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);
        when(conversationMapper.updateById(any(CompanionConversation.class))).thenReturn(1);

        Long conversationId = service.startTurn(req);

        assertEquals(203L, conversationId);
        ArgumentCaptor<CompanionConversation> conversationCaptor = ArgumentCaptor.forClass(CompanionConversation.class);
        verify(conversationMapper).updateById(conversationCaptor.capture());
        assertEquals("KT12", conversationCaptor.getValue().getNodeCode());
    }

    @Test
    void startTurnRejectsForeignConversationId() {
        req.setConversationId(201L);

        CompanionConversation existingConversation = new CompanionConversation();
        existingConversation.setId(201L);
        existingConversation.setStudentId(2L);
        existingConversation.setCourseId(10L);
        when(conversationMapper.selectById(201L)).thenReturn(existingConversation);

        assertThatThrownBy(() -> service.startTurn(req))
                .isInstanceOf(com.wenjin.common.BusinessException.class)
                .hasMessageContaining("无权访问该会话");

        verify(conversationMapper, never()).updateById(any(CompanionConversation.class));
        verify(messageMapper, never()).insert(any(CompanionMessage.class));
    }

    @Test
    void startTurnRejectsConversationIdFromAnotherCourse() {
        req.setConversationId(202L);

        CompanionConversation existingConversation = new CompanionConversation();
        existingConversation.setId(202L);
        existingConversation.setStudentId(1L);
        existingConversation.setCourseId(11L);
        when(conversationMapper.selectById(202L)).thenReturn(existingConversation);

        assertThatThrownBy(() -> service.startTurn(req))
                .isInstanceOf(com.wenjin.common.BusinessException.class)
                .hasMessageContaining("无权访问该会话");

        verify(conversationMapper, never()).updateById(any(CompanionConversation.class));
        verify(messageMapper, never()).insert(any(CompanionMessage.class));
    }

    @Test
    void startTurnRejectsMessageOverInputLimit() {
        req.setMessage("x".repeat(4001));
        assertThatThrownBy(() -> service.startTurn(req))
                .isInstanceOf(com.wenjin.common.BusinessException.class)
                .extracting(e -> ((com.wenjin.common.BusinessException) e).getCode())
                .isEqualTo(com.wenjin.common.ResultCode.PAYLOAD_TOO_LARGE.getCode());
        verify(messageMapper, never()).insert(any(CompanionMessage.class));
    }

    @Test
    void streamReplyWhenToolsDisabledUsesLegacyStreamAndPersistsReply() {
        Long conversationId = 300L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("什么是二次函数？")));
        configureTools(false, 2, 3, List.of());

        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            onToken.accept("二次");
            onToken.accept("函数");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        StringBuilder streamed = new StringBuilder();
        service.streamReply(conversationId, streamed::append);

        assertEquals("二次函数", streamed.toString());
        verify(aiClient).stream(any(), any(), any(), any());
        verify(aiClient, never()).streamTurn(any(), any(), any(), any());

        ArgumentCaptor<CompanionMessage> messageCaptor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper, times(1)).insert(messageCaptor.capture());
        CompanionMessage savedMessage = messageCaptor.getValue();
        assertEquals(ROLE_AI, savedMessage.getRole());
        assertEquals("二次函数", savedMessage.getContent());
    }

    @Test
    void streamReplyTruncatesAiOutputOverLimitWithReason() {
        LimitsProperties tight = new LimitsProperties();
        tight.getCompanion().setMaxAiOutputChars(10);
        ReflectionTestUtils.setField(service, "limits", tight);
        ReflectionTestUtils.setField(service, "contextBuilder", new ConversationContextBuilder(tight));

        Long conversationId = 301L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("q")));
        configureTools(false, 2, 3, List.of());

        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            onToken.accept("0123456789ABCDEF"); // 16 chars → cap 10
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        StringBuilder streamed = new StringBuilder();
        service.streamReply(conversationId, streamed::append);

        assertThat(streamed.toString()).startsWith("0123456789");
        assertThat(streamed.toString()).contains("回复已截断");
        ArgumentCaptor<CompanionMessage> messageCaptor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getContent()).contains("回复已截断");
    }

    @Test
    void streamReplyWithHundredMessages_usesSummaryAndRecentWindow() {
        Long conversationId = 302L;
        List<CompanionMessage> history = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            CompanionMessage m = userMessage("msg-" + i);
            m.setId((long) i);
            m.setRole(i % 2 == 1 ? ROLE_USER : ROLE_AI);
            history.add(m);
        }
        stubConversationAndPrompt(conversationId, history);
        configureTools(false, 2, 3, List.of());

        ArgumentCaptor<List> historyCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> latestCaptor = ArgumentCaptor.forClass(String.class);
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            onToken.accept("ok");
            return null;
        }).when(aiClient).stream(any(), historyCaptor.capture(), latestCaptor.capture(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);
        when(conversationMapper.updateSummaryGuarded(eq(conversationId), anyString(), anyLong())).thenReturn(1);

        service.streamReply(conversationId, t -> { });

        assertThat(latestCaptor.getValue()).isEqualTo("msg-100");
        // 近窗 20 条中除最新外 19 条 + 摘要伪对话 2 条
        assertThat(historyCaptor.getValue().size()).isLessThan(100);
        boolean hasSummary = historyCaptor.getValue().stream()
                .anyMatch(o -> o instanceof CompanionAiClient.ChatMsg m
                        && m.content() != null
                        && m.content().contains("历史摘要"));
        assertThat(hasSummary).isTrue();
        // P0-1：摘要走守卫回写，禁止整实体 updateById（避免覆盖并发节点切换）
        verify(conversationMapper).updateSummaryGuarded(eq(conversationId), anyString(), anyLong());
        verify(conversationMapper, never()).updateById(any(CompanionConversation.class));
    }

    @Test
    void streamReplyWhenSummaryWatermarkAlreadyAdvancedSkipsWriteAndContinues() {
        Long conversationId = 303L;
        List<CompanionMessage> history = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            CompanionMessage m = userMessage("msg-" + i);
            m.setId((long) i);
            m.setRole(i % 2 == 1 ? ROLE_USER : ROLE_AI);
            history.add(m);
        }
        stubConversationAndPrompt(conversationId, history);
        configureTools(false, 2, 3, List.of());

        when(conversationMapper.updateSummaryGuarded(eq(conversationId), anyString(), anyLong())).thenReturn(0);
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            onToken.accept("still-ok");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        StringBuilder streamed = new StringBuilder();
        service.streamReply(conversationId, streamed::append);

        assertEquals("still-ok", streamed.toString());
        verify(conversationMapper).updateSummaryGuarded(eq(conversationId), anyString(), anyLong());
        verify(conversationMapper, never()).updateById(any(CompanionConversation.class));
        verify(messageMapper).insert(any(CompanionMessage.class));
    }

    @Test
    void streamReplyLoadsHistoryViaSelectSinceIdAscWithWatermark() {
        Long conversationId = 314L;
        Long throughId = 40L;
        List<CompanionMessage> incremental = new ArrayList<>();
        for (int i = 41; i <= 50; i++) {
            CompanionMessage m = userMessage("msg-" + i);
            m.setId((long) i);
            m.setRole(i % 2 == 1 ? ROLE_USER : ROLE_AI);
            incremental.add(m);
        }
        stubConversationAndPromptWithWatermark(conversationId, throughId, incremental);
        configureTools(false, 2, 3, List.of());

        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            onToken.accept("ok");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, t -> { });

        // 生产路径：按 watermark 增量加载，不得回退 selectList
        verify(messageMapper).selectSinceIdAsc(eq(conversationId), eq(throughId));
        verify(messageMapper, never()).selectList(any());
    }

    @Test
    void streamReplyWhenToolsDisabledUsesLegacyPromptGrounding() {
        Long conversationId = 304L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("review first")));
        stubWeakPoint();
        configureTools(false, 2, 3, List.of(fakeTool(
                "query_mastery",
                "query mastery",
                Map.of("type", "object", "properties", Map.of()))));

        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            onToken.accept("review graph");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, token -> { });

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiClient).stream(promptCaptor.capture(), any(), any(), any());
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("\u0023\u0023 \u5b66\u751f\u8584\u5f31\u70b9");
        assertThat(prompt).contains("\u638c\u63e1\u5ea6 45");
        assertThat(prompt).doesNotContain("\u0023\u0023 \u5de5\u5177\u4f7f\u7528");
        assertThat(prompt).doesNotContain("\u7981\u6b62\u7f16\u9020\u638c\u63e1\u5ea6");
    }

    @Test
    void streamReplyWhenToolsEnabledButNoSchemasFallsBackWithoutToolOrchestration() {
        Long conversationId = 305L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("what next")));
        stubWeakPoint();
        configureTools(true, 2, 3, List.of());

        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            onToken.accept("鍏堣ˉ鍑芥暟鍥惧儚");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, token -> { });

        verify(aiClient).stream(any(), any(), any(), any());
        verify(aiClient, never()).streamTurn(any(), any(), any(), any());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiClient).stream(promptCaptor.capture(), any(), any(), any());
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("\u0023\u0023 \u5b66\u751f\u8584\u5f31\u70b9");
        assertThat(prompt).doesNotContain("\u0023\u0023 \u5de5\u5177\u4f7f\u7528");
    }

    @Test
    void streamReplyWhenMaxRoundsZeroFallsBackWithoutToolPrompt() {
        Long conversationId = 306L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("what next")));
        stubWeakPoint();
        configureTools(true, 0, 3, List.of(fakeTool(
                "query_mastery",
                "query mastery",
                Map.of("type", "object", "properties", Map.of()))));

        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            onToken.accept("鍏堣ˉ鍑芥暟鍥惧儚");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, token -> { });

        verify(aiClient).stream(any(), any(), any(), any());
        verify(aiClient, never()).streamTurn(any(), any(), any(), any());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiClient).stream(promptCaptor.capture(), any(), any(), any());
        assertThat(promptCaptor.getValue()).doesNotContain("\u0023\u0023 \u5de5\u5177\u4f7f\u7528");
    }

    @Test
    void streamReplyWhenMaxToolCallsZeroFallsBackWithoutToolPrompt() {
        Long conversationId = 307L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("what next")));
        stubWeakPoint();
        configureTools(true, 2, 0, List.of(fakeTool(
                "query_mastery",
                "query mastery",
                Map.of("type", "object", "properties", Map.of()))));

        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            onToken.accept("鍏堣ˉ鍑芥暟鍥惧儚");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, token -> { });

        verify(aiClient).stream(any(), any(), any(), any());
        verify(aiClient, never()).streamTurn(any(), any(), any(), any());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiClient).stream(promptCaptor.capture(), any(), any(), any());
        assertThat(promptCaptor.getValue()).doesNotContain("\u0023\u0023 \u5de5\u5177\u4f7f\u7528");
    }

    @Test
    void directAnswerUsesExactlyOneStreamTurnAndStreamsTokensLive() {
        Long conversationId = 308L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("need advice")));
        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery",
                "正在查询你的掌握情况…",
                Map.of("type", "object", "properties", Map.of()))));

        scriptTurns(text("direct ", "final ", "answer"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertEquals(List.of("direct ", "final ", "answer"), sink.tokens);
        assertThat(sink.toolStarts).isEmpty();
        assertThat(sink.toolResults).isEmpty();
        verify(aiClient, times(1)).streamTurn(any(), any(), any(), any());
        verify(aiClient, never()).completeWithTools(any(), any());
        verify(aiClient, never()).streamMessages(any(), any());
        verify(toolGovernor, never()).execute(any(), any(), any(), any());

        assertThat(turnChoices).containsExactly(CompanionAiClient.ToolChoice.AUTO);
        assertThat(turnSchemas.get(0)).hasSize(1);
        assertThat(turnMessages.get(0)).hasSize(2);
        assertThat(turnMessages.get(0).get(1))
                .containsEntry("role", "user")
                .containsEntry("content", "need advice");

        assertEquals("direct final answer", persistedReply());
    }

    @Test
    void streamReplyWithToolsIncludesNodeCodeLookupInSystemPrompt() {
        Long conversationId = 318L;
        KgNode focusNode = new KgNode();
        focusNode.setId(12L);
        focusNode.setNodeCode("KT12");
        focusNode.setName("二次函数基础");
        KgNode namedNode = new KgNode();
        namedNode.setId(99L);
        namedNode.setNodeCode("KT99");
        namedNode.setName("数组");
        // Intent routing would short-circuit on practice phrasing; this test targets system prompt only.
        stubConversationAndPrompt(conversationId, List.of(userMessage("介绍一下数组")),
                List.of(focusNode, namedNode), focusNode);
        configureTools(true, 2, 3, List.of(fakeTool(
                "start_practice",
                "正在生成节点练习…",
                Map.of("type", "object", "properties", Map.of()))));

        scriptTurns(text("数组是…"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, token -> { });

        String systemPrompt = (String) turnMessages.get(0).get(0).get("content");
        assertThat(systemPrompt).contains("- 数组（nodeCode: KT99）");
        assertThat(systemPrompt).contains("能确定 nodeCode 就用 nodeCode，否则传精确 nodeName 让服务端解析");
    }

    @Test
    void streamReplyWithPracticeIntentForcesStartPracticeThenOneNoneSummaryRound() {
        Long conversationId = 501L;
        KgNode seNode = new KgNode();
        seNode.setId(1L);
        seNode.setNodeCode("KT01");
        seNode.setName("软件工程概述");
        stubConversationAndPrompt(conversationId,
                List.of(userMessage("给我出两道关于软件工程概述的题")),
                List.of(seNode), seNode);
        configureTools(true, 2, 3, List.of(fakeTool(
                "start_practice",
                "正在生成节点练习…",
                Map.of("type", "object", "properties", Map.of()))));

        PracticeStartVO card = practiceCard(901L, 1L, "KT01", "软件工程概述", "What is software engineering?");
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("intent-start-practice", "start_practice", true,
                        "{\"sessionId\":901,\"questionCount\":2}", card));
        scriptTurns(text("练习已准备好"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        ArgumentCaptor<CompanionAiClient.ToolCall> callCaptor =
                ArgumentCaptor.forClass(CompanionAiClient.ToolCall.class);
        verify(toolGovernor).execute(any(ToolContext.class), callCaptor.capture(), any(ToolExecutionState.class), any());
        CompanionAiClient.ToolCall forced = callCaptor.getValue();
        assertEquals("intent-start-practice", forced.id());
        assertEquals("start_practice", forced.name());
        assertThat(forced.argumentsJson()).contains("nodeCode");
        assertThat(forced.argumentsJson()).contains("KT01");
        assertThat(forced.argumentsJson()).contains("\"size\":2");

        assertThat(sink.toolStarts).containsExactly("start_practice|正在生成节点练习…");
        assertThat(sink.toolResults).containsExactly("start_practice|true");
        assertThat(sink.practiceCards).containsExactly(card);
        assertEquals(List.of("练习已准备好"), sink.tokens);
        verify(aiClient, times(1)).streamTurn(any(), any(), any(), any());
        assertThat(turnChoices).containsExactly(CompanionAiClient.ToolChoice.NONE);
        assertThat(turnSchemas.get(0)).isNotEmpty();
        verify(aiClient, never()).completeWithTools(any(), any());
    }

    @Test
    void streamReplyWithPureQuestionDoesNotForcePractice() {
        Long conversationId = 502L;
        KgNode seNode = new KgNode();
        seNode.setId(1L);
        seNode.setNodeCode("KT01");
        seNode.setName("软件工程概述");
        stubConversationAndPrompt(conversationId,
                List.of(userMessage("软件工程概述是什么？")),
                List.of(seNode), seNode);
        configureTools(true, 2, 3, List.of(fakeTool(
                "start_practice",
                "正在生成节点练习…",
                Map.of("type", "object", "properties", Map.of()))));

        scriptTurns(text("软件工程概述是…"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        verify(aiClient, times(1)).streamTurn(any(), any(), any(), any());
        assertThat(turnChoices).containsExactly(CompanionAiClient.ToolChoice.AUTO);
        verify(toolGovernor, never()).execute(any(), any(), any(), any());
        assertThat(sink.practiceCards).isEmpty();
        assertEquals(List.of("软件工程概述是…"), sink.tokens);
    }

    @Test
    void streamReplyWhenIntentRoutingDisabledLetsModelDecideForPracticePhrase() {
        Long conversationId = 503L;
        KgNode seNode = new KgNode();
        seNode.setId(1L);
        seNode.setNodeCode("KT01");
        seNode.setName("软件工程概述");
        stubConversationAndPrompt(conversationId,
                List.of(userMessage("给我出两道关于软件工程概述的题")),
                List.of(seNode), seNode);
        configureTools(true, 2, 3, List.of(fakeTool(
                "start_practice",
                "正在生成节点练习…",
                Map.of("type", "object", "properties", Map.of()))), false);

        scriptTurns(text("你可以自己练习一下"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        verify(aiClient, times(1)).streamTurn(any(), any(), any(), any());
        assertThat(turnChoices).containsExactly(CompanionAiClient.ToolChoice.AUTO);
        verify(toolGovernor, never()).execute(any(), any(), any(), any());
        assertThat(sink.practiceCards).isEmpty();
        assertEquals(List.of("你可以自己练习一下"), sink.tokens);
    }

    @Test
    void streamReplyAfterToolRoundStreamsLiveInArrivalOrder() {
        Long conversationId = 309L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("need advice")));
        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery",
                "正在查询你的掌握情况…",
                Map.of("type", "object", "properties", Map.of()))));

        scriptTurns(
                new Turn(List.of("我先查一下。"),
                        List.of(new CompanionAiClient.ToolCall("call-1", "query_mastery", "{}")), null, null),
                text("streamed ", "after tool"));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("call-1", "query_mastery", true, "{\"items\":[]}"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertThat(sink.events).containsExactly(
                "token|我先查一下。",
                "tool_start|query_mastery|正在查询你的掌握情况…",
                "tool_result|query_mastery|true",
                "token|streamed ",
                "token|after tool");
        verify(aiClient, times(2)).streamTurn(any(), any(), any(), any());
        // pre-tool text is replayed to the model as the assistant tool_calls message content
        assertThat(turnMessages.get(1).get(2))
                .containsEntry("role", "assistant")
                .containsEntry("content", "我先查一下。")
                .containsKey("tool_calls");
        // rounds separated by a blank line when a tool round intervenes
        assertEquals("我先查一下。\n\nstreamed after tool", persistedReply());
    }

    @Test
    void streamReplyEmitsCodeResultRightAfterToolResultAndOfferedOnlyWhenAvailable() {
        Long conversationId = 311L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("帮我看看这段代码为什么报错")));
        configureTools(true, 2, 3, List.of(codeTool(true)));
        scriptTurns(
                tools(new CompanionAiClient.ToolCall("call-1", "run_code_check", "{\"code\":\"class Main{}\"}")),
                text("编译器说缺少分号"));
        com.wenjin.dto.CodeResultVO card = new com.wenjin.dto.CodeResultVO("RUN", "COMPILE_ERROR", null, null,
                "Main.java:3: error", "", "", List.of(), 12L, null);
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("call-1", "run_code_check", true, "{\"ok\":true}",
                        null, false, false, card));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertThat(sink.events).containsExactly(
                "tool_start|run_code_check|正在运行你的代码…",
                "tool_result|run_code_check|true",
                "code_result|COMPILE_ERROR",
                "token|编译器说缺少分号");
        assertThat(turnSchemas.get(0)).anyMatch(t -> t.toString().contains("run_code_check"));
        String prompt = String.valueOf(turnMessages.get(0).get(0).get("content"));
        assertThat(prompt).contains("run_code_check").contains("## 代码求助").contains("不要替学生完整重写作业");
    }

    @Test
    void unavailableCodeToolIsDroppedFromSchemaAndPrompt() {
        Long conversationId = 312L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("帮我看看这段代码")));
        configureTools(true, 2, 3, List.of(codeTool(false), fakeTool(
                "query_mastery", "查询", Map.of("type", "object", "properties", Map.of()))));
        scriptTurns(text("好的"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, new RecordingSink());

        assertThat(turnSchemas.get(0)).noneMatch(t -> t.toString().contains("run_code_check"));
        assertThat(turnSchemas.get(0)).anyMatch(t -> t.toString().contains("query_mastery"));
        String prompt = String.valueOf(turnMessages.get(0).get(0).get("content"));
        assertThat(prompt).doesNotContain("run_code_check").doesNotContain("## 代码求助");
    }

    private CompanionTool codeTool(boolean available) {
        CompanionTool base = fakeTool("run_code_check", "运行代码", Map.of("type", "object", "properties", Map.of()));
        return new CompanionTool() {
            @Override
            public String name() {
                return base.name();
            }

            @Override
            public Map<String, Object> schema() {
                return base.schema();
            }

            @Override
            public String label() {
                return "正在运行你的代码…";
            }

            @Override
            public boolean available() {
                return available;
            }

            @Override
            public String execute(ToolContext ctx, com.fasterxml.jackson.databind.JsonNode args) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void streamReplyStripsDsmlFinalTokensAndPersistsCleanText() {
        Long conversationId = 401L;
        // Practice phrasing + focus node → intent routing forces start_practice.
        stubConversationAndPrompt(conversationId, List.of(userMessage("给我出两道软件工程概述的题")));
        configureTools(true, 2, 3, List.of(fakeTool(
                "start_practice",
                "正在生成节点练习…",
                Map.of("type", "object", "properties", Map.of()))));

        PracticeStartVO card = practiceCard(901L, 12L, "KT12", "软件工程概述", "What is software engineering?");
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("intent-start-practice", "start_practice", true, "{\"ok\":true}", card));
        scriptTurns(text("<|DSML|tool_calls>junk</|DSML|tool_calls>"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        String joined = String.join("", sink.tokens);
        assertThat(joined).doesNotContain("DSML");
        assertThat(joined).doesNotContain("tool_calls");
        assertThat(joined).isNotBlank();
        assertThat(joined).contains("练习");
        assertThat(sink.practiceCards).containsExactly(card);
        verify(aiClient, never()).completeWithTools(any(), any());

        String persisted = persistedReply();
        assertThat(persisted).doesNotContain("DSML");
        assertThat(persisted).isEqualTo(joined);
    }

    @Test
    void streamReplyWithPracticeCardResultEmitsCardToSinkAndKeepsToolMessageMinimal() {
        Long conversationId = 310L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("start practice")));
        configureTools(true, 2, 3, List.of(fakeTool(
                "start_practice",
                "正在生成节点练习…",
                Map.of("type", "object", "properties", Map.of()))));

        PracticeStartVO card = practiceCard(901L, 12L, "KT12", "Quadratic Functions", "What shape is the graph?");
        scriptTurns(
                tools(new CompanionAiClient.ToolCall("call-1", "start_practice", "{\"nodeCode\":\"KT12\"}")),
                text("practice is ready"));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult(
                        "call-1",
                        "start_practice",
                        true,
                        "{\"sessionId\":901,\"questionCount\":1}",
                        card));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertEquals(List.of("practice is ready"), sink.tokens);
        assertThat(sink.practiceCards).containsExactly(card);

        List<Map<String, Object>> secondRoundMessages = turnMessages.get(1);
        assertThat(secondRoundMessages.get(secondRoundMessages.size() - 1))
                .containsEntry("role", "tool")
                .containsEntry("tool_call_id", "call-1")
                .containsEntry("content", "{\"sessionId\":901,\"questionCount\":1}");
    }

    @Test
    void streamReplyWithPracticeCardResultEmitsToolResultBeforePracticeCard() {
        Long conversationId = 311L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("start practice")));
        configureTools(true, 2, 3, List.of(fakeTool(
                "start_practice",
                "正在生成节点练习…",
                Map.of("type", "object", "properties", Map.of()))));

        PracticeStartVO card = practiceCard(901L, 12L, "KT12", "Quadratic Functions", "What shape is the graph?");
        scriptTurns(
                tools(new CompanionAiClient.ToolCall("call-1", "start_practice", "{\"nodeCode\":\"KT12\"}")),
                text("practice is ready"));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult(
                        "call-1",
                        "start_practice",
                        true,
                        "{\"sessionId\":901,\"questionCount\":1}",
                        card));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertThat(sink.events).containsSubsequence(
                "tool_start|start_practice|正在生成节点练习…",
                "tool_result|start_practice|true",
                "practice_card|901",
                "token|practice is ready");
    }

    @Test
    void streamReplyWithToolsExecutesToolCallsThenStreamsFinalReply() {
        Long conversationId = 301L;
        stubConversationAndPrompt(conversationId, List.of(
                userMessage("上次的问题"),
                aiMessage("上次的回答"),
                userMessage("我现在该补什么？")));

        CompanionTool tool = fakeTool(
                "query_mastery",
                "查询掌握度",
                Map.of("type", "object", "properties", Map.of()));
        configureTools(true, 2, 3, List.of(tool));

        scriptTurns(
                tools(new CompanionAiClient.ToolCall("call-1", "query_mastery", "{\"nodeCode\":\"KT12\"}")),
                text("先补", "二次函数基础"));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("call-1", "query_mastery", true, "{\"nodeCode\":\"KT12\",\"effectiveScore\":42}"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        // Live streaming: each delta is forwarded as it arrives.
        assertEquals(List.of("先补", "二次函数基础"), sink.tokens);
        assertEquals(List.of("query_mastery|查询掌握度"), sink.toolStarts);
        assertEquals(List.of("query_mastery|true"), sink.toolResults);

        assertThat(turnSchemas.get(0)).hasSize(1);
        assertThat(turnSchemas.get(0).get(0)).containsEntry("type", "function");

        ArgumentCaptor<ToolContext> ctxCaptor = ArgumentCaptor.forClass(ToolContext.class);
        ArgumentCaptor<CompanionAiClient.ToolCall> callCaptor = ArgumentCaptor.forClass(CompanionAiClient.ToolCall.class);
        verify(toolGovernor).execute(ctxCaptor.capture(), callCaptor.capture(), any(ToolExecutionState.class), any());
        assertEquals(1L, ctxCaptor.getValue().studentId());
        assertEquals(10L, ctxCaptor.getValue().courseId());
        assertEquals("query_mastery", callCaptor.getValue().name());

        List<Map<String, Object>> messages = turnMessages.get(1);
        assertThat(messages).hasSize(6);
        assertThat(messages.get(0)).containsEntry("role", "system");
        assertThat(messages.get(1)).containsEntry("role", "user").containsEntry("content", "上次的问题");
        assertThat(messages.get(2)).containsEntry("role", "assistant").containsEntry("content", "上次的回答");
        assertThat(messages.get(3)).containsEntry("role", "user").containsEntry("content", "我现在该补什么？");
        assertThat(messages.get(4)).containsEntry("role", "assistant");
        assertThat(messages.get(4)).containsKey("tool_calls");
        assertThat(messages.get(5)).containsEntry("role", "tool");
        assertThat(messages.get(5)).containsEntry("tool_call_id", "call-1");
        assertThat(messages.get(5)).containsEntry("content", "{\"nodeCode\":\"KT12\",\"effectiveScore\":42}");

        verify(messageMapper, times(1)).insert(any(CompanionMessage.class));
        assertEquals("先补二次函数基础", persistedReply());
    }

    @Test
    void finalRoundUsesToolChoiceNoneAndStillCarriesTools() {
        Long conversationId = 302L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("继续")));

        configureTools(true, 1, 3, List.of(fakeTool(
                "query_mastery",
                "查询掌握度",
                Map.of("type", "object", "properties", Map.of()))));

        scriptTurns(
                tools(new CompanionAiClient.ToolCall("call-1", "query_mastery", "{}")),
                text("继续回答"));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("call-1", "query_mastery", true, "{\"items\":[]}"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, token -> { });

        verify(aiClient, times(2)).streamTurn(any(), any(), any(), any());
        assertThat(turnChoices).containsExactly(
                CompanionAiClient.ToolChoice.AUTO, CompanionAiClient.ToolChoice.NONE);
        assertThat(turnSchemas.get(1)).hasSize(1);
        List<Map<String, Object>> messages = turnMessages.get(1);
        assertThat(messages).hasSize(4);
        assertThat(messages.get(2)).containsEntry("role", "assistant");
        assertThat(messages.get(3)).containsEntry("role", "tool").containsEntry("content", "{\"items\":[]}");
    }

    @Test
    void toolBudgetExhaustedSwitchesNextRoundToNone() {
        Long conversationId = 320L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("查一下")));
        configureTools(true, 3, 1, List.of(fakeTool(
                "query_mastery",
                "查询掌握度",
                Map.of("type", "object", "properties", Map.of()))));

        scriptTurns(
                tools(new CompanionAiClient.ToolCall("call-1", "query_mastery", "{}")),
                text("好的"));
        when(toolGovernor.execute(any(), any(), any(), any())).thenAnswer(inv -> {
            ToolExecutionState state = inv.getArgument(2);
            state.tryConsume(false);
            return new ToolExecutionResult("call-1", "query_mastery", true, "{\"items\":[]}");
        });
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, token -> { });

        assertThat(turnChoices).containsExactly(
                CompanionAiClient.ToolChoice.AUTO, CompanionAiClient.ToolChoice.NONE);
        assertThat(turnSchemas.get(1)).isNotEmpty();
    }

    @Test
    void parallelReadOnlyToolsRunConcurrentlyAndResultsStayInCallOrder() {
        Long conversationId = 321L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("我的情况")));
        configureTools(true, 2, 4, List.of(
                fakeTool("query_mastery", "查询掌握度", Map.of("type", "object", "properties", Map.of())),
                fakeTool("get_node_detail", "节点详情", Map.of("type", "object", "properties", Map.of()))));

        scriptTurns(
                tools(new CompanionAiClient.ToolCall("call-a", "query_mastery", "{}"),
                        new CompanionAiClient.ToolCall("call-b", "get_node_detail", "{}")),
                text("综合来看"));
        java.util.concurrent.CountDownLatch bStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean overlapped = new java.util.concurrent.atomic.AtomicBoolean();
        when(toolGovernor.execute(any(), any(), any(), any())).thenAnswer(inv -> {
            CompanionAiClient.ToolCall call = inv.getArgument(1);
            if ("call-a".equals(call.id())) {
                // A only finishes once B has started → proves concurrent execution; A also finishes last.
                overlapped.set(bStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
                return new ToolExecutionResult("call-a", "query_mastery", true, "{\"a\":1}");
            }
            bStarted.countDown();
            return new ToolExecutionResult("call-b", "get_node_detail", true, "{\"b\":2}");
        });
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertThat(overlapped.get()).isTrue();
        assertThat(sink.events).containsExactly(
                "tool_start|query_mastery|查询掌握度",
                "tool_start|get_node_detail|节点详情",
                "tool_result|query_mastery|true",
                "tool_result|get_node_detail|true",
                "token|综合来看");
        List<Map<String, Object>> messages = turnMessages.get(1);
        assertThat(messages.get(messages.size() - 2)).containsEntry("tool_call_id", "call-a");
        assertThat(messages.get(messages.size() - 1)).containsEntry("tool_call_id", "call-b");
    }

    @Test
    void cancelDuringParallelBatchSkipsApplyingResults() {
        Long conversationId = 324L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("我的情况")));
        configureTools(true, 2, 4, List.of(
                fakeTool("query_mastery", "查询掌握度", Map.of("type", "object", "properties", Map.of())),
                fakeTool("get_node_detail", "节点详情", Map.of("type", "object", "properties", Map.of()))));
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        com.wenjin.service.plan.PlanCancelGuard guard =
                org.mockito.Mockito.mock(com.wenjin.service.plan.PlanCancelGuard.class);
        when(guard.isCancelRequested(any(com.wenjin.service.trace.TraceContext.class)))
                .thenAnswer(inv -> cancel.get());
        ReflectionTestUtils.setField(service, "planCancelGuard", guard);
        scriptTurns(
                tools(new CompanionAiClient.ToolCall("call-a", "query_mastery", "{}"),
                        new CompanionAiClient.ToolCall("call-b", "get_node_detail", "{}")),
                text("综合来看"));
        when(toolGovernor.execute(any(), any(), any(), any())).thenAnswer(inv -> {
            CompanionAiClient.ToolCall call = inv.getArgument(1);
            cancel.set(true); // 学生在工具执行期间点了停止
            return new ToolExecutionResult(call.id(), call.name(), true, "{}");
        });

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertThat(sink.events).noneMatch(e -> e.startsWith("tool_result"));
        verify(aiClient, times(1)).streamTurn(any(), any(), any(), any());
    }

    @Test
    void streamReplyWhenToolFailsAppendsToolErrorAndStillStreamsFinalReply() {
        Long conversationId = 303L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("给我建议")));

        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery",
                "查询掌握度",
                Map.of("type", "object", "properties", Map.of()))));

        scriptTurns(
                tools(new CompanionAiClient.ToolCall("call-err", "query_mastery", "{}")),
                text("先从概念开始"));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("call-err", "query_mastery", false,
                        "{\"ok\":false,\"code\":\"TOOL_INTERNAL\",\"message\":\"工具执行失败\",\"retryable\":true}"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertEquals(List.of("query_mastery|false"), sink.toolResults);
        List<Map<String, Object>> messages = turnMessages.get(1);
        assertThat(messages.get(messages.size() - 1))
                .containsEntry("role", "tool")
                .containsEntry("tool_call_id", "call-err")
                .containsEntry("content", "{\"ok\":false,\"code\":\"TOOL_INTERNAL\",\"message\":\"工具执行失败\",\"retryable\":true}");

        assertEquals("先从概念开始", persistedReply());
    }

    @Test
    void streamReplyWhenFirstRoundFailsBeforeAnyTextFallsBackToLegacyStreamAndPersistsReply() {
        Long conversationId = 312L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("need fallback")));
        stubWeakPoint();
        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery",
                "query mastery",
                Map.of("type", "object", "properties", Map.of()))));

        scriptTurns(fail(new IllegalStateException("stage one boom")));
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            onToken.accept("legacy fallback reply");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertEquals(List.of("legacy fallback reply"), sink.tokens);
        assertThat(sink.toolStarts).isEmpty();
        assertThat(sink.toolResults).isEmpty();
        verify(aiClient, times(1)).streamTurn(any(), any(), any(), any());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiClient).stream(promptCaptor.capture(), any(), any(), any());
        assertThat(promptCaptor.getValue()).doesNotContain("## 工具使用");
        assertThat(promptCaptor.getValue()).contains("## 学生薄弱点");
        verify(toolGovernor, never()).execute(any(), any(), any(), any());

        assertEquals("legacy fallback reply", persistedReply());
    }

    @Test
    void streamReplyWhenLaterRoundFailsRetriesFinalRoundWithAccumulatedToolContext() {
        Long conversationId = 313L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("need evidence fallback")));
        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery",
                "query mastery",
                Map.of("type", "object", "properties", Map.of()))));

        scriptTurns(
                tools(new CompanionAiClient.ToolCall("call-1", "query_mastery", "{\"nodeCode\":\"KT12\"}")),
                fail(new IllegalStateException("second round boom")),
                text("fallback uses tool context"));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("call-1", "query_mastery", true,
                        "{\"nodeCode\":\"KT12\",\"effectiveScore\":42}"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertEquals(List.of("fallback uses tool context"), sink.tokens);
        verify(aiClient, times(3)).streamTurn(any(), any(), any(), any());
        verify(aiClient, never()).stream(any(), any(), any(), any());
        assertThat(turnChoices.get(2)).isEqualTo(CompanionAiClient.ToolChoice.NONE);

        List<Map<String, Object>> messages = turnMessages.get(2);
        assertThat(messages).hasSize(4);
        assertThat((String) messages.get(0).get("content")).doesNotContain("## 工具使用");
        assertThat(messages.get(2)).containsEntry("role", "assistant").containsKey("tool_calls");
        assertThat(messages.get(3))
                .containsEntry("role", "tool")
                .containsEntry("tool_call_id", "call-1")
                .containsEntry("content", "{\"nodeCode\":\"KT12\",\"effectiveScore\":42}");

        assertEquals("fallback uses tool context", persistedReply());
    }

    @Test
    void llmFailureAfterTextWasEmittedSendsNoticePersistsAndDoesNotDegrade() {
        Long conversationId = 322L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("讲讲二次函数")));
        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery", "查询掌握度", Map.of("type", "object", "properties", Map.of()))));
        com.wenjin.service.companion.CompanionDegradeService degradeService =
                org.mockito.Mockito.mock(com.wenjin.service.companion.CompanionDegradeService.class);
        ReflectionTestUtils.setField(service, "degradeService", degradeService);

        scriptTurns(fail(new IllegalStateException("upstream reset"), "二次函数的图像", "是抛物线"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertThat(sink.tokens).hasSize(3);
        assertThat(sink.tokens.subList(0, 2)).containsExactly("二次函数的图像", "是抛物线");
        assertThat(sink.tokens.get(2)).contains("回答中断");
        verify(degradeService, never()).degrade(any(), any(), any(), any(), any());
        verify(aiClient, never()).stream(any(), any(), any(), any());
        verify(aiClient, times(1)).streamTurn(any(), any(), any(), any());
        assertThat(persistedReply()).startsWith("二次函数的图像是抛物线").contains("回答中断");
    }

    // ── I3 先落库再推送：客户端已断开时回复不丢 ───────────────────────────────

    private static CompanionService.CompanionStreamSink brokenSink() {
        return new CompanionService.CompanionStreamSink() {
            @Override
            public void token(String token) {
                throw new IllegalStateException("broken pipe");
            }
        };
    }

    @Test
    void killSwitchDegrade_persistsBeforeEmit_whenClientDisconnected() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setCompanionLlmEnabled(false);
        ReflectionTestUtils.setField(service, "featureFlags", flags);
        com.wenjin.service.companion.CompanionDegradeService degradeService =
                org.mockito.Mockito.mock(com.wenjin.service.companion.CompanionDegradeService.class);
        ReflectionTestUtils.setField(service, "degradeService", degradeService);
        when(degradeService.degrade(any(), any(), any(), any(), any())).thenReturn(
                new com.wenjin.service.companion.CompanionDegradeService.DegradeResult("先复习一下吧", null, "x"));
        stubConversationAndPrompt(330L, List.of(userMessage("你好")));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(330L, brokenSink());

        assertThat(persistedReply()).isEqualTo("先复习一下吧");
    }

    @Test
    void killSwitchWithoutDegradeService_persistsFallbackBeforeEmit() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setCompanionLlmEnabled(false);
        ReflectionTestUtils.setField(service, "featureFlags", flags);
        stubConversationAndPrompt(331L, List.of(userMessage("你好")));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(331L, brokenSink());

        assertThat(persistedReply()).contains("学习伴侣暂时不可用");
    }

    @Test
    void orchestratorDegrade_persistsBeforeEmit_whenClientDisconnected() {
        stubConversationAndPrompt(332L, List.of(userMessage("讲讲二次函数")));
        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery", "查询掌握度", Map.of("type", "object", "properties", Map.of()))));
        com.wenjin.service.companion.CompanionDegradeService degradeService =
                org.mockito.Mockito.mock(com.wenjin.service.companion.CompanionDegradeService.class);
        ReflectionTestUtils.setField(service, "degradeService", degradeService);
        when(degradeService.degrade(any(), any(), any(), any(), any())).thenReturn(
                new com.wenjin.service.companion.CompanionDegradeService.DegradeResult("降级回复", null, "x"));
        scriptTurns(fail(new IllegalStateException("read timed out")));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(332L, brokenSink());

        assertThat(persistedReply()).isEqualTo("降级回复");
    }

    @Test
    void orchestratorFinishFallback_persistsBeforeEmit_whenClientDisconnected() {
        stubConversationAndPrompt(333L, List.of(userMessage("我掌握得怎么样")));
        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery", "查询掌握度", Map.of("type", "object", "properties", Map.of()))));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("c1", "query_mastery", true, "{}"));
        scriptTurns(tools(new CompanionAiClient.ToolCall("c1", "query_mastery", "{}")), text());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);
        // toolStart/toolResult 正常，只有 token 断开
        service.streamReply(333L, brokenSink());

        assertThat(persistedReply()).isNotBlank();
    }

    @Test
    void sinkDisconnectStopsWithoutDegradeAndNeverTouchesSinkAgain() {
        Long conversationId = 323L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("讲讲二次函数")));
        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery", "查询掌握度", Map.of("type", "object", "properties", Map.of()))));
        com.wenjin.service.companion.CompanionDegradeService degradeService =
                org.mockito.Mockito.mock(com.wenjin.service.companion.CompanionDegradeService.class);
        ReflectionTestUtils.setField(service, "degradeService", degradeService);

        // Mimic the real client: callback exceptions surface as SinkFailureException.
        when(aiClient.streamTurn(any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<String> cb = inv.getArgument(3);
            cb.accept("第一段");
            try {
                cb.accept("第二段");
            } catch (RuntimeException e) {
                throw new com.wenjin.ai.SinkFailureException(e);
            }
            cb.accept("第三段");
            return new CompanionAiClient.StreamTurnResult("第一段第二段第三段", List.of(), null, "stop");
        });
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        List<String> delivered = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger callsAfterFailure = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        CompanionService.CompanionStreamSink sink = new CompanionService.CompanionStreamSink() {
            @Override
            public void token(String token) {
                if (failed.get()) {
                    callsAfterFailure.incrementAndGet();
                }
                if (delivered.size() == 1) {
                    failed.set(true);
                    throw new IllegalStateException("broken pipe");
                }
                delivered.add(token);
            }

            @Override
            public void probeCard(ProbeStartVO card) {
                callsAfterFailure.incrementAndGet();
            }
        };

        service.streamReply(conversationId, sink);

        assertThat(delivered).containsExactly("第一段");
        assertThat(callsAfterFailure.get()).isZero();
        verify(degradeService, never()).degrade(any(), any(), any(), any(), any());
        verify(aiClient, times(1)).streamTurn(any(), any(), any(), any());
        verify(aiClient, never()).stream(any(), any(), any(), any());
        assertThat(persistedReply()).startsWith("第一段");
    }

    // ── T7 错因辅导 ───────────────────────────────────────────────────────

    @Test
    void toolPrompt_listsRecentMistakesToolAndMisconceptionRules() {
        String prompt = CompanionServiceImpl.buildSystemPrompt("软件工程", List.of("用例图（nodeCode: SE-3.2）"),
                List.of(), null, null, null, null, null);
        assertThat(prompt).contains("get_recent_mistakes").contains("## 错因辅导").contains("为什么会选错");
        assertThat(prompt.indexOf("## 错因辅导")).isLessThan(prompt.indexOf("## 课程知识点范围"));
    }

    @Test
    void reviewPracticeSession_flowsIntoToolContextAndPrompt() {
        configureTools(true, 2, 3, List.of(fakeTool(
                "get_recent_mistakes", "正在翻看你的错题…", Map.of("type", "object", "properties", Map.of()))));
        stubConversationAndPrompt(610L, List.of(userMessage("帮我复盘一下刚才那次练习的错题")));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("c1", "get_recent_mistakes", true, "{\"mistakes\":[],\"note\":\"近期没有错题\"}"));
        scriptTurns(tools(new CompanionAiClient.ToolCall("c1", "get_recent_mistakes", "{}")), text("这次没有错题"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(610L, 77L, new RecordingSink());

        ArgumentCaptor<ToolContext> ctxCaptor = ArgumentCaptor.forClass(ToolContext.class);
        verify(toolGovernor).execute(ctxCaptor.capture(), any(), any(), any());
        assertThat(ctxCaptor.getValue().reviewPracticeSessionId()).isEqualTo(77L);
        assertThat(String.valueOf(turnMessages.get(0).get(0).get("content")))
                .contains("## 本轮复盘").contains("#77");
    }

    @Test
    void reviewSession_withStartPracticeAndFocus_doesNotForcePractice() {
        configureTools(true, 2, 3, List.of(
                fakeTool("start_practice", "正在生成节点练习…", Map.of("type", "object", "properties", Map.of())),
                fakeTool("get_recent_mistakes", "正在翻看你的错题…", Map.of("type", "object", "properties", Map.of()))));
        // 带焦点节点 KT12；用户原话含「练习」
        stubConversationAndPrompt(613L, List.of(userMessage("帮我复盘一下刚才那次练习的错题")));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("c1", "get_recent_mistakes", true, "{\"mistakes\":[]}"));
        scriptTurns(tools(new CompanionAiClient.ToolCall("c1", "get_recent_mistakes", "{}")), text("没有错题"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(613L, 78L, new RecordingSink());

        ArgumentCaptor<CompanionAiClient.ToolCall> callCaptor =
                ArgumentCaptor.forClass(CompanionAiClient.ToolCall.class);
        verify(toolGovernor).execute(any(), callCaptor.capture(), any(), any());
        assertThat(callCaptor.getAllValues()).extracting(CompanionAiClient.ToolCall::name)
                .containsExactly("get_recent_mistakes");
    }

    @Test
    void typedReviewRequest_withoutReviewSession_doesNotForcePractice() {
        configureTools(true, 2, 3, List.of(
                fakeTool("start_practice", "正在生成节点练习…", Map.of("type", "object", "properties", Map.of())),
                fakeTool("get_recent_mistakes", "正在翻看你的错题…", Map.of("type", "object", "properties", Map.of()))));
        stubConversationAndPrompt(614L, List.of(userMessage("帮我复盘一下刚才那次练习的错题")));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("c1", "get_recent_mistakes", true, "{\"mistakes\":[]}"));
        scriptTurns(tools(new CompanionAiClient.ToolCall("c1", "get_recent_mistakes", "{}")), text("没有错题"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(614L, new RecordingSink());

        ArgumentCaptor<CompanionAiClient.ToolCall> callCaptor =
                ArgumentCaptor.forClass(CompanionAiClient.ToolCall.class);
        verify(toolGovernor).execute(any(), callCaptor.capture(), any(), any());
        assertThat(callCaptor.getAllValues()).extracting(CompanionAiClient.ToolCall::name)
                .containsExactly("get_recent_mistakes");
    }

    @Test
    void thinkingModeReasoningIsReplayedOnAssistantToolCallMessageOnly() {
        configureTools(true, 2, 3, List.of(fakeTool(
                "get_recent_mistakes", "正在翻看你的错题…", Map.of("type", "object", "properties", Map.of()))));
        stubConversationAndPrompt(615L, List.of(userMessage("我最近哪里错得多")));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("c1", "get_recent_mistakes", true, "{\"mistakes\":[]}"));
        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger();
        when(aiClient.streamTurn(any(), any(), any(), any())).thenAnswer(inv -> {
            List<Map<String, Object>> msgs = inv.getArgument(0);
            turnMessages.add(new ArrayList<>(msgs));
            if (idx.getAndIncrement() == 0) {
                return new CompanionAiClient.StreamTurnResult("",
                        List.of(new CompanionAiClient.ToolCall("c1", "get_recent_mistakes", "{}")),
                        null, "tool_calls", "先查错题再回答");
            }
            Consumer<String> cb = inv.getArgument(3);
            cb.accept("没有错题");
            return new CompanionAiClient.StreamTurnResult("没有错题", List.of(), null, "stop");
        });
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);
        RecordingSink sink = new RecordingSink();

        service.streamReply(615L, sink);

        Map<String, Object> assistant = turnMessages.get(1).stream()
                .filter(m -> "assistant".equals(m.get("role")) && m.containsKey("tool_calls"))
                .findFirst().orElseThrow();
        assertThat(assistant.get("reasoning_content")).isEqualTo("先查错题再回答");
        assertThat(sink.tokens).containsExactly("没有错题");
        assertThat(persistedReply()).isEqualTo("没有错题");
    }

    @Test
    void assistantToolCallMessageOmitsReasoningWhenAbsent() {
        Map<String, Object> plain = com.wenjin.service.companion.CompanionOrchestrator.assistantToolCallMessage(
                "", List.of(new CompanionAiClient.ToolCall("c1", "x", "{}")), null);
        assertThat(plain).doesNotContainKey("reasoning_content");
        Map<String, Object> empty = com.wenjin.service.companion.CompanionOrchestrator.assistantToolCallMessage(
                "", List.of(new CompanionAiClient.ToolCall("c1", "x", "{}")), "");
        assertThat(empty).doesNotContainKey("reasoning_content");
    }

    @Test
    void withoutReviewSession_promptHasNoReviewSection() {
        configureTools(true, 2, 3, List.of(fakeTool(
                "get_recent_mistakes", "正在翻看你的错题…", Map.of("type", "object", "properties", Map.of()))));
        stubConversationAndPrompt(611L, List.of(userMessage("你好")));
        scriptTurns(text("你好呀"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(611L, new RecordingSink());

        assertThat(String.valueOf(turnMessages.get(0).get(0).get("content"))).doesNotContain("## 本轮复盘");
    }

    @Test
    void mistakesTurn_autoProbeTargetsFirstMisconceptionNode() {
        com.wenjin.service.companion.ProbeService probeService =
                org.mockito.Mockito.mock(com.wenjin.service.companion.ProbeService.class);
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        ReflectionTestUtils.setField(service, "featureFlags", flags);
        ReflectionTestUtils.setField(service, "reflectionCoordinator",
                new com.wenjin.service.companion.ReflectionCoordinator(flags, probeService));
        ProbeStartVO probe = new ProbeStartVO();
        when(probeService.startProbe(1L, 10L, "KT05")).thenReturn(probe);

        configureTools(true, 2, 3, List.of(fakeTool(
                "get_recent_mistakes", "正在翻看你的错题…", Map.of("type", "object", "properties", Map.of()))));
        stubConversationAndPrompt(612L, List.of(userMessage("帮我复盘一下错题")));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(new ToolExecutionResult("c1",
                "get_recent_mistakes", true,
                "{\"mistakes\":[{\"questionId\":1}],\"topMisconceptions\":[{\"nodeCode\":\"KT05\",\"name\":\"一次函数\",\"hits\":2},"
                        + "{\"nodeCode\":\"KT06\",\"name\":\"x\",\"hits\":1}]}"));
        scriptTurns(tools(new CompanionAiClient.ToolCall("c1", "get_recent_mistakes", "{}")),
                text("你把一次函数和二次函数混了"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(612L, sink);

        assertThat(sink.probeCards).containsExactly(probe);
        verify(probeService).startProbe(1L, 10L, "KT05");
        verify(probeService, never()).startProbe(1L, 10L, "KT12");
    }

    @Test
    void mistakesTurn_withoutMisconceptions_probesConversationNode() {
        com.wenjin.service.companion.ProbeService probeService =
                org.mockito.Mockito.mock(com.wenjin.service.companion.ProbeService.class);
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        ReflectionTestUtils.setField(service, "featureFlags", flags);
        ReflectionTestUtils.setField(service, "reflectionCoordinator",
                new com.wenjin.service.companion.ReflectionCoordinator(flags, probeService));
        when(probeService.startProbe(1L, 10L, "KT12")).thenReturn(new ProbeStartVO());

        configureTools(true, 2, 3, List.of(fakeTool(
                "get_recent_mistakes", "正在翻看你的错题…", Map.of("type", "object", "properties", Map.of()))));
        stubConversationAndPrompt(613L, List.of(userMessage("帮我复盘一下错题")));
        when(toolGovernor.execute(any(), any(), any(), any())).thenReturn(new ToolExecutionResult("c1",
                "get_recent_mistakes", true, "{\"mistakes\":[],\"note\":\"近期没有错题\"}"));
        scriptTurns(tools(new CompanionAiClient.ToolCall("c1", "get_recent_mistakes", "{}")), text("没有错题"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(613L, new RecordingSink());

        verify(probeService).startProbe(1L, 10L, "KT12");
    }

    @Test
    void failedProbe_nextTurnInjectsStrategySwitchAndReprobes() {
        com.wenjin.service.companion.ProbeService probeService =
                org.mockito.Mockito.mock(com.wenjin.service.companion.ProbeService.class);
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        com.wenjin.service.companion.ProbeOutcomeStore outcomes = new com.wenjin.service.companion.ProbeOutcomeStore();
        outcomes.recordProbeResult(1L, 10L, "KT12", false);
        ReflectionTestUtils.setField(service, "featureFlags", flags);
        ReflectionTestUtils.setField(service, "reflectionCoordinator",
                new com.wenjin.service.companion.ReflectionCoordinator(flags, probeService, outcomes));
        ProbeStartVO probe = new ProbeStartVO();
        when(probeService.startProbe(1L, 10L, "KT12")).thenReturn(probe);

        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery", "查询掌握度", Map.of("type", "object", "properties", Map.of()))));
        // frontend "换个方式再讲" style message without explain keywords → classifier says CHAT
        stubConversationAndPrompt(614L, List.of(userMessage("我还是不太懂，换个方式再来一遍")));
        scriptTurns(text("打个比方…"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(614L, sink);

        String systemPrompt = String.valueOf(turnMessages.get(0).get(0).get("content"));
        assertThat(systemPrompt).contains("## 换种讲法").contains("『类比』").contains("KT12")
                .contains("避免重复上次的讲法");
        assertThat(sink.probeCards).containsExactly(probe);
        assertThat(outcomes.latest(1L, 10L, "KT12").orElseThrow().switchConsumed()).isTrue();

        // the same failure does not switch again on the following turn
        turnMessages.clear();
        service.streamReply(614L, new RecordingSink());
        assertThat(String.valueOf(turnMessages.get(0).get(0).get("content"))).doesNotContain("## 换种讲法");
    }

    @Test
    void reflectionDisabled_noStrategySwitchInjected() {
        com.wenjin.service.companion.ProbeService probeService =
                org.mockito.Mockito.mock(com.wenjin.service.companion.ProbeService.class);
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setReflectionEnabled(false);
        com.wenjin.service.companion.ProbeOutcomeStore outcomes = new com.wenjin.service.companion.ProbeOutcomeStore();
        outcomes.recordProbeResult(1L, 10L, "KT12", false);
        ReflectionTestUtils.setField(service, "featureFlags", flags);
        ReflectionTestUtils.setField(service, "reflectionCoordinator",
                new com.wenjin.service.companion.ReflectionCoordinator(flags, probeService, outcomes));

        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery", "查询掌握度", Map.of("type", "object", "properties", Map.of()))));
        stubConversationAndPrompt(615L, List.of(userMessage("我还是不太懂，换个方式再来一遍")));
        scriptTurns(text("好的"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(615L, sink);

        assertThat(String.valueOf(turnMessages.get(0).get(0).get("content"))).doesNotContain("## 换种讲法");
        assertThat(sink.probeCards).isEmpty();
        verify(probeService, never()).startProbe(any(), any(), any());
        assertThat(outcomes.latest(1L, 10L, "KT12").orElseThrow().switchConsumed()).isFalse();
    }

    @Test
    void explainIntentTriggersAutoProbeButChatDoesNot() {
        com.wenjin.service.companion.ProbeService probeService =
                org.mockito.Mockito.mock(com.wenjin.service.companion.ProbeService.class);
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setReflectionEnabled(true);
        ReflectionTestUtils.setField(service, "featureFlags", flags);
        ReflectionTestUtils.setField(service, "reflectionCoordinator",
                new com.wenjin.service.companion.ReflectionCoordinator(flags, probeService));
        ProbeStartVO probe = new ProbeStartVO();
        when(probeService.startProbe(1L, 10L, "KT12")).thenReturn(probe);

        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery", "查询掌握度", Map.of("type", "object", "properties", Map.of()))));
        stubConversationAndPrompt(330L, List.of(userMessage("什么是二次函数？")));
        scriptTurns(text("二次函数是…"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink explainSink = new RecordingSink();
        service.streamReply(330L, explainSink);
        assertThat(explainSink.probeCards).containsExactly(probe);

        stubConversationAndPrompt(331L, List.of(userMessage("你好呀")));
        RecordingSink chatSink = new RecordingSink();
        service.streamReply(331L, chatSink);
        assertThat(chatSink.probeCards).isEmpty();
        verify(probeService, times(1)).startProbe(any(), any(), any());
    }

    @Test
    void streamReply_whenMemoryEnabled_injectsMemoryLinesIntoToolContext() {
        Long conversationId = 910L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("继续上次")));
        configureTools(true, 2, 4, List.of(fakeTool(
                "query_mastery",
                "查询掌握度",
                Map.of("type", "object", "properties", Map.of()))));

        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setMemoryEnabled(true);
        ReflectionTestUtils.setField(service, "featureFlags", flags);
        ReflectionTestUtils.setField(service, "studentMemoryService", studentMemoryService);
        when(studentMemoryService.loadInjectLines(1L, 10L)).thenReturn(List.of(
                "【学生画像】",
                "· 偏好代码示例",
                "【近期学习经历】",
                "· 用例图练习受挫（SE-3.2）"));

        scriptTurns(text("好的，我们按代码示例继续。"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, t -> { });

        String joined = turnMessages.get(0).stream()
                .map(m -> String.valueOf(m.get("content")))
                .collect(Collectors.joining("\n"));
        assertThat(joined).contains("【记忆要点】");
        assertThat(joined).contains("【学生画像】");
        assertThat(joined).contains("偏好代码示例");
        assertThat(joined).contains("用例图练习受挫");
        verify(studentMemoryService).loadInjectLines(1L, 10L);
    }

    @Test
    void streamReply_whenMemoryDisabled_skipsInjectAndHidesMemoryTools() {
        Long conversationId = 911L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("你好")));
        configureTools(true, 2, 4, List.of(
                fakeTool("recall_student_context", "回忆", Map.of("type", "object", "properties", Map.of())),
                fakeTool("record_observation", "记录", Map.of("type", "object", "properties", Map.of())),
                fakeTool("query_mastery", "掌握度", Map.of("type", "object", "properties", Map.of()))));

        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setMemoryEnabled(false);
        ReflectionTestUtils.setField(service, "featureFlags", flags);
        ReflectionTestUtils.setField(service, "studentMemoryService", studentMemoryService);

        scriptTurns(text("hi"));
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, t -> { });

        verify(studentMemoryService, never()).loadInjectLines(any(), any());
        List<String> names = turnSchemas.get(0).stream()
                .map(s -> {
                    Object fn = s.get("function");
                    if (fn instanceof Map<?, ?> m) {
                        return String.valueOf(m.get("name"));
                    }
                    return "";
                })
                .toList();
        assertThat(names).contains("query_mastery");
        assertThat(names).doesNotContain("recall_student_context", "record_observation");

        String joined = turnMessages.get(0).stream()
                .map(m -> String.valueOf(m.get("content")))
                .collect(Collectors.joining("\n"));
        assertThat(joined).doesNotContain("【记忆要点】");
        assertThat(joined).doesNotContain("【学生画像】");
    }

    @Test
    void buildPromptOmitsWeakPointSectionAndAddsToolGuidance() {
        LearningPathVO.NodeRef targetNode = new LearningPathVO.NodeRef();
        targetNode.setNodeCode("KT20");
        targetNode.setName("一元二次方程");

        LearningPathVO.StepVO currentStep = new LearningPathVO.StepVO();
        currentStep.setNodeCode("KT12");
        currentStep.setName("二次函数基础");

        String prompt = CompanionServiceImpl.buildSystemPrompt(
                "数据结构",
                Arrays.asList("链表", "二叉树"),
                Arrays.asList("函数图像（掌握度 45）", "配方法（掌握度 38）"),
                targetNode,
                currentStep,
                "KT12",
                "二次函数基础",
                null);

        assertFalse(prompt.contains("## 学生薄弱点"));
        assertFalse(prompt.contains("函数图像（掌握度 45）"));
        assertThat(prompt).contains("## 工具使用");
        assertThat(prompt).contains("掌握度");
        assertThat(prompt).contains("节点详情");
        assertThat(prompt).contains("练习");
        assertThat(prompt).contains("禁止编造掌握度");
        assertThat(prompt).contains("禁止在回复正文中输出");
        assertThat(prompt).contains("只用自然语言总结结果");
        assertThat(prompt).contains("当前学习路径");
        assertThat(prompt).contains("当前焦点节点");
        assertThat(prompt).contains("直接答案");
        assertThat(prompt).contains("分步引导");
    }

    @Test
    void buildPromptInjectsDiagnosticRootCause() {
        DiagnosticResultVO diagnostic = new DiagnosticResultVO();
        diagnostic.setHasWeakness(true);

        DiagnosticResultVO.NodeRef stuckNode = new DiagnosticResultVO.NodeRef();
        stuckNode.setName("软件测试");
        diagnostic.setStuckNode(stuckNode);

        DiagnosticResultVO.RootCause rootCause = new DiagnosticResultVO.RootCause();
        rootCause.setName("需求分析");
        rootCause.setMasteryScore(42.0);
        rootCause.setSelf(false);
        diagnostic.setRootCause(rootCause);

        DiagnosticResultVO.ChainNode chain1 = new DiagnosticResultVO.ChainNode();
        chain1.setName("需求分析");
        DiagnosticResultVO.ChainNode chain2 = new DiagnosticResultVO.ChainNode();
        chain2.setName("软件测试");
        diagnostic.setChain(List.of(chain1, chain2));

        String prompt = CompanionServiceImpl.buildSystemPrompt(
                null,
                List.of("需求分析", "软件测试"),
                List.of(),
                null,
                null,
                null,
                null,
                diagnostic);

        assertTrue(prompt.contains("## 诊断根因"));
        assertTrue(prompt.contains("需求分析"));
        assertTrue(prompt.contains("前置"));
        assertTrue(prompt.contains("软件测试"));
        assertTrue(prompt.contains("42"));
    }

    @Test
    void buildPromptFallsBackToCourseWhenNameNullOrBlank() {
        String promptNull = CompanionServiceImpl.buildSystemPrompt(
                null,
                List.of("链表"),
                List.of(),
                null,
                null,
                null,
                null,
                null);
        assertThat(promptNull).contains("《课程》");
        assertThat(promptNull).doesNotContain("软件工程");

        String promptBlank = CompanionServiceImpl.buildSystemPrompt(
                "",
                List.of("链表"),
                List.of(),
                null,
                null,
                null,
                null,
                null);
        assertThat(promptBlank).contains("《课程》");
        assertThat(promptBlank).doesNotContain("软件工程");
    }

    @Test
    void buildPromptPutsStaticSectionsFirstAndStudentSectionsLast() {
        DiagnosticResultVO diagnostic = new DiagnosticResultVO();
        diagnostic.setHasWeakness(true);
        DiagnosticResultVO.NodeRef stuckNode = new DiagnosticResultVO.NodeRef();
        stuckNode.setName("软件测试");
        diagnostic.setStuckNode(stuckNode);
        DiagnosticResultVO.RootCause rootCause = new DiagnosticResultVO.RootCause();
        rootCause.setName("需求分析");
        rootCause.setMasteryScore(42.0);
        rootCause.setSelf(false);
        diagnostic.setRootCause(rootCause);

        LearningPathVO.NodeRef targetNode = new LearningPathVO.NodeRef();
        targetNode.setNodeCode("KT20");
        targetNode.setName("一元二次方程");

        String prompt = CompanionServiceImpl.buildSystemPrompt(
                "数据结构", List.of("链表（nodeCode: KT01）"), List.of(), targetNode, null,
                "KT12", "二次函数基础", diagnostic);

        int persona = prompt.indexOf("你是「问津」");
        int rules = prompt.indexOf("## 对话规则");
        int tools = prompt.indexOf("## 工具使用");
        int whitelist = prompt.indexOf("## 课程知识点范围");
        int root = prompt.indexOf("## 诊断根因");
        int path = prompt.indexOf("## 当前学习路径");
        int focus = prompt.indexOf("## 当前焦点节点");
        assertThat(persona).isZero();
        assertThat(rules).isGreaterThan(persona);
        assertThat(tools).isGreaterThan(rules);
        assertThat(whitelist).isGreaterThan(tools);
        assertThat(root).isGreaterThan(whitelist);
        assertThat(path).isGreaterThan(root);
        assertThat(focus).isGreaterThan(path);

        // Static prefix is identical across students (prompt-cache friendly).
        String other = CompanionServiceImpl.buildSystemPrompt(
                "数据结构", List.of("链表（nodeCode: KT01）"), List.of(), null, null, null, null, null);
        assertThat(other).isEqualTo(prompt.substring(0, root));

        for (String tool : List.of("query_mastery", "get_node_detail", "start_practice", "trace_prerequisites",
                "get_learning_frontier", "get_review_queue", "schedule_review", "recall_student_context",
                "record_observation", "search_materials", "probe_understanding", "draft_study_plan",
                "get_plan_progress")) {
            assertThat(prompt.substring(tools, whitelist)).contains(tool);
        }
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

    private void configureTools(boolean enabled, int maxRounds, int maxToolCalls, List<CompanionTool> tools) {
        configureTools(enabled, maxRounds, maxToolCalls, tools, true);
    }

    private void configureTools(boolean enabled,
                                int maxRounds,
                                int maxToolCalls,
                                List<CompanionTool> tools,
                                boolean intentRoutingEnabled) {
        CompanionToolProperties properties = new CompanionToolProperties();
        properties.setEnabled(enabled);
        properties.setMaxRounds(maxRounds);
        properties.setMaxToolCalls(maxToolCalls);
        properties.setIntentRoutingEnabled(intentRoutingEnabled);
        ReflectionTestUtils.setField(service, "toolProperties", properties);
        ReflectionTestUtils.setField(service, "companionTools", tools);
        ReflectionTestUtils.setField(service, "toolGovernor", toolGovernor);
    }

    private void stubConversationAndPrompt(Long conversationId, List<CompanionMessage> history) {
        KgNode whitelistNode = new KgNode();
        whitelistNode.setId(12L);
        whitelistNode.setNodeCode("KT12");
        whitelistNode.setName("二次函数基础");
        stubConversationAndPrompt(conversationId, history, List.of(whitelistNode), whitelistNode);
    }

    private void stubConversationAndPrompt(Long conversationId,
                                           List<CompanionMessage> history,
                                           List<KgNode> whitelistNodes,
                                           KgNode focusNode) {
        CompanionConversation conversation = new CompanionConversation();
        conversation.setId(conversationId);
        conversation.setStudentId(1L);
        conversation.setCourseId(10L);
        conversation.setNodeCode("KT12");
        when(conversationMapper.selectById(conversationId)).thenReturn(conversation);
        // P0-2 生产路径：增量加载 selectSinceIdAsc（afterId = summaryThroughMessageId，默认 null）
        when(messageMapper.selectSinceIdAsc(eq(conversationId), any())).thenReturn(history);
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(whitelistNodes);
        when(pathService.getCurrent(1L, 10L)).thenReturn(path());
        when(diagnosticResultService.getResult(1L, 10L)).thenReturn(null);
        when(courseMapper.selectById(10L)).thenReturn(null);
        when(nodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(focusNode);
    }

    /**
     * 与 {@link #stubConversationAndPrompt} 相同，并设置 summary watermark，
     * 使 streamReply 以 afterId = throughId 调用 selectSinceIdAsc。
     */
    private CompanionConversation stubConversationAndPromptWithWatermark(Long conversationId,
                                                                         Long summaryThroughMessageId,
                                                                         List<CompanionMessage> history) {
        KgNode whitelistNode = new KgNode();
        whitelistNode.setId(12L);
        whitelistNode.setNodeCode("KT12");
        whitelistNode.setName("二次函数基础");

        CompanionConversation conversation = new CompanionConversation();
        conversation.setId(conversationId);
        conversation.setStudentId(1L);
        conversation.setCourseId(10L);
        conversation.setNodeCode("KT12");
        conversation.setSummaryText("【历史摘要】已折叠的旧消息");
        conversation.setSummaryThroughMessageId(summaryThroughMessageId);
        when(conversationMapper.selectById(conversationId)).thenReturn(conversation);
        when(messageMapper.selectSinceIdAsc(eq(conversationId), eq(summaryThroughMessageId))).thenReturn(history);
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(whitelistNode));
        when(pathService.getCurrent(1L, 10L)).thenReturn(path());
        when(diagnosticResultService.getResult(1L, 10L)).thenReturn(null);
        when(courseMapper.selectById(10L)).thenReturn(null);
        when(nodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(whitelistNode);
        return conversation;
    }

    private void stubWeakPoint() {
        StudentMastery mastery = new StudentMastery();
        mastery.setNodeId(99L);
        mastery.setMasteryScore(new java.math.BigDecimal("45.0"));
        mastery.setMasteryLevel(1);
        mastery.setUpdatedAt(LocalDateTime.now());
        when(masteryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(mastery));

        KgNode weakNode = new KgNode();
        weakNode.setId(99L);
        weakNode.setName("鍑芥暟鍥惧儚");
        when(nodeMapper.selectBatchIds(List.of(99L))).thenReturn(List.of(weakNode));
    }

    private LearningPathVO path() {
        LearningPathVO path = new LearningPathVO();
        LearningPathVO.NodeRef targetNode = new LearningPathVO.NodeRef();
        targetNode.setNodeCode("KT20");
        targetNode.setName("一元二次方程");
        path.setTargetNode(targetNode);

        LearningPathVO.StepVO currentStep = new LearningPathVO.StepVO();
        currentStep.setNodeCode("KT12");
        currentStep.setName("二次函数基础");
        currentStep.setStatus(0);
        path.setSteps(List.of(currentStep));
        return path;
    }

    private CompanionMessage userMessage(String content) {
        CompanionMessage message = new CompanionMessage();
        message.setRole(ROLE_USER);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private CompanionMessage aiMessage(String content) {
        CompanionMessage message = new CompanionMessage();
        message.setRole(ROLE_AI);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private CompanionTool fakeTool(String name, String description, Map<String, Object> parameters) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

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
                throw new UnsupportedOperationException("not used in this test");
            }
        };
    }

    private PracticeStartVO practiceCard(Long sessionId,
                                         Long nodeId,
                                         String nodeCode,
                                         String nodeName,
                                         String stem) {
        PracticeStartVO card = new PracticeStartVO();
        card.setSessionId(sessionId);
        PracticeStartVO.NodeRef nodeRef = new PracticeStartVO.NodeRef();
        nodeRef.setNodeId(nodeId);
        nodeRef.setNodeCode(nodeCode);
        nodeRef.setName(nodeName);
        card.setNode(nodeRef);

        PaperQuestionVO question = new PaperQuestionVO();
        question.setQuestionId(700L);
        question.setStem(stem);
        PaperQuestionVO.OptionVO option = new PaperQuestionVO.OptionVO();
        option.setKey("A");
        option.setText("Parabola");
        question.setOptions(List.of(option));
        card.setQuestions(List.of(question));
        return card;
    }

    private static final int ROLE_USER = 1;
    private static final int ROLE_AI = 2;

    // ── scripted streamTurn ────────────────────────────────────────────────

    /** One scripted LLM round: deltas are pushed to the content callback, then error (if any) is thrown. */
    private record Turn(List<String> deltas,
                        List<CompanionAiClient.ToolCall> calls,
                        CompanionAiClient.Usage usage,
                        RuntimeException error) {
    }

    private static Turn text(String... deltas) {
        return new Turn(List.of(deltas), List.of(), null, null);
    }

    private static Turn tools(CompanionAiClient.ToolCall... calls) {
        return new Turn(List.of(), List.of(calls), null, null);
    }

    private static Turn fail(RuntimeException error, String... deltasBefore) {
        return new Turn(List.of(deltasBefore), List.of(), null, error);
    }

    /** Per-call snapshots (the orchestrator mutates its message list between rounds). */
    private final List<List<Map<String, Object>>> turnMessages = new ArrayList<>();
    private final List<List<Map<String, Object>>> turnSchemas = new ArrayList<>();
    private final List<CompanionAiClient.ToolChoice> turnChoices = new ArrayList<>();

    private void scriptTurns(Turn... turns) {
        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger();
        when(aiClient.streamTurn(any(), any(), any(), any())).thenAnswer(inv -> {
            List<Map<String, Object>> msgs = inv.getArgument(0);
            turnMessages.add(new ArrayList<>(msgs));
            turnSchemas.add(inv.getArgument(1));
            turnChoices.add(inv.getArgument(2));
            Turn t = turns[Math.min(idx.getAndIncrement(), turns.length - 1)];
            Consumer<String> cb = inv.getArgument(3);
            for (String d : t.deltas()) {
                cb.accept(d);
            }
            if (t.error() != null) {
                throw t.error();
            }
            String content = String.join("", t.deltas());
            return new CompanionAiClient.StreamTurnResult(content, t.calls(), t.usage(),
                    t.calls().isEmpty() ? "stop" : "tool_calls");
        });
    }

    private String persistedReply() {
        ArgumentCaptor<CompanionMessage> captor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper).insert(captor.capture());
        return captor.getValue().getContent();
    }

    private static final class RecordingSink extends CompanionService.CompanionStreamSink {
        private final List<String> tokens = new ArrayList<>();
        private final List<String> toolStarts = new ArrayList<>();
        private final List<String> toolResults = new ArrayList<>();
        private final List<PracticeStartVO> practiceCards = new ArrayList<>();
        private final List<ProbeStartVO> probeCards = new ArrayList<>();
        private final List<String> events = new ArrayList<>();

        @Override
        public void probeCard(ProbeStartVO card) {
            probeCards.add(card);
            events.add("probe_card");
        }

        @Override
        public void token(String token) {
            tokens.add(token);
            events.add("token|" + token);
        }

        @Override
        public void toolStart(String name, String label) {
            toolStarts.add(name + "|" + label);
            events.add("tool_start|" + name + "|" + label);
        }

        @Override
        public void toolResult(String name, boolean ok) {
            toolResults.add(name + "|" + ok);
            events.add("tool_result|" + name + "|" + ok);
        }

        @Override
        public void codeResult(com.wenjin.dto.CodeResultVO card) {
            events.add("code_result|" + card.verdict());
        }

        @Override
        public void practiceCard(PracticeStartVO card) {
            practiceCards.add(card);
            events.add("practice_card|" + (card == null ? "null" : card.getSessionId()));
        }
    }
}
