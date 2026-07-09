package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.dto.CompanionChatRequest;
import com.wenjin.dto.DiagnosticResultVO;
import com.wenjin.dto.LearningPathVO;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.dto.PracticeStartVO;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    private CompanionServiceImpl service;
    private CompanionChatRequest req;

    @BeforeEach
    void setUp() {
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
                courseService);

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
        verify(aiClient, never()).completeWithTools(any(), any());
        verify(aiClient, never()).streamMessages(any(), any());

        ArgumentCaptor<CompanionMessage> messageCaptor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper, times(1)).insert(messageCaptor.capture());
        CompanionMessage savedMessage = messageCaptor.getValue();
        assertEquals(ROLE_AI, savedMessage.getRole());
        assertEquals("二次函数", savedMessage.getContent());
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
        verify(aiClient, never()).completeWithTools(any(), any());
        verify(aiClient, never()).streamMessages(any(), any());

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
        verify(aiClient, never()).completeWithTools(any(), any());
        verify(aiClient, never()).streamMessages(any(), any());

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
        verify(aiClient, never()).completeWithTools(any(), any());
        verify(aiClient, never()).streamMessages(any(), any());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiClient).stream(promptCaptor.capture(), any(), any(), any());
        assertThat(promptCaptor.getValue()).doesNotContain("\u0023\u0023 \u5de5\u5177\u4f7f\u7528");
    }

    @Test
    void streamReplyWithToolsTreatsMeaningfulFinalTextAsStopSignalAndStreamsFinalReply() {
        Long conversationId = 308L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("need advice")));
        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery",
                "正在查询你的掌握情况…",
                Map.of("type", "object", "properties", Map.of()))));

        when(aiClient.completeWithTools(any(), any()))
                .thenReturn(new CompanionAiClient.FinalText("direct final answer"));
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(1);
            onToken.accept("streamed final answer");
            return null;
        }).when(aiClient).streamMessages(any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertEquals(List.of("streamed final answer"), sink.tokens);
        assertThat(sink.toolStarts).isEmpty();
        assertThat(sink.toolResults).isEmpty();
        verify(aiClient, times(1)).completeWithTools(any(), any());
        verify(aiClient).streamMessages(any(), any());
        verify(toolGovernor, never()).execute(any(), any(), any());

        ArgumentCaptor<List<Map<String, Object>>> streamMessagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiClient).streamMessages(streamMessagesCaptor.capture(), any());
        assertThat(streamMessagesCaptor.getValue()).hasSize(2);
        assertThat(streamMessagesCaptor.getValue().get(1))
                .containsEntry("role", "user")
                .containsEntry("content", "need advice");

        ArgumentCaptor<CompanionMessage> messageCaptor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertEquals("streamed final answer", messageCaptor.getValue().getContent());
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
        stubConversationAndPrompt(conversationId, List.of(userMessage("给我出两道数组的题")),
                List.of(focusNode, namedNode), focusNode);
        configureTools(true, 2, 3, List.of(fakeTool(
                "start_practice",
                "正在生成节点练习…",
                Map.of("type", "object", "properties", Map.of()))));

        when(aiClient.completeWithTools(any(), any()))
                .thenReturn(new CompanionAiClient.FinalText(""));
        doAnswer(invocation -> null).when(aiClient).streamMessages(any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, token -> { });

        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiClient).completeWithTools(messagesCaptor.capture(), any());
        String systemPrompt = (String) messagesCaptor.getValue().get(0).get("content");
        assertThat(systemPrompt).contains("- 数组（nodeCode: KT99）");
        assertThat(systemPrompt).contains("能确定 nodeCode 就用 nodeCode，否则传精确 nodeName 让服务端解析");
    }

    @Test
    void streamReplyWithToolsTreatsMeaningfulFinalTextAfterToolRoundAsStopSignalAndStreamsFinalReply() {
        Long conversationId = 309L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("need advice")));
        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery",
                "正在查询你的掌握情况…",
                Map.of("type", "object", "properties", Map.of()))));

        when(aiClient.completeWithTools(any(), any())).thenReturn(
                new CompanionAiClient.ToolCalls(List.of(
                        new CompanionAiClient.ToolCall("call-1", "query_mastery", "{}"))),
                new CompanionAiClient.FinalText("answer after tool"));
        when(toolGovernor.execute(any(), any(), any())).thenReturn(
                new ToolExecutionResult("call-1", "query_mastery", true, "{\"items\":[]}"));
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(1);
            onToken.accept("streamed after tool");
            return null;
        }).when(aiClient).streamMessages(any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertEquals(List.of("streamed after tool"), sink.tokens);
        assertEquals(List.of("query_mastery|正在查询你的掌握情况…"), sink.toolStarts);
        assertEquals(List.of("query_mastery|true"), sink.toolResults);
        verify(aiClient, times(2)).completeWithTools(any(), any());
        verify(aiClient).streamMessages(any(), any());

        ArgumentCaptor<CompanionMessage> messageCaptor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertEquals("streamed after tool", messageCaptor.getValue().getContent());
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
        when(aiClient.completeWithTools(any(), any())).thenReturn(
                new CompanionAiClient.ToolCalls(List.of(
                        new CompanionAiClient.ToolCall("call-1", "start_practice", "{\"nodeCode\":\"KT12\"}"))),
                new CompanionAiClient.FinalText("practice is ready"));
        when(toolGovernor.execute(any(), any(), any())).thenReturn(
                new ToolExecutionResult(
                        "call-1",
                        "start_practice",
                        true,
                        "{\"sessionId\":901,\"questionCount\":1}",
                        card));
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(1);
            onToken.accept("practice is ready");
            return null;
        }).when(aiClient).streamMessages(any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertEquals(List.of("practice is ready"), sink.tokens);
        assertThat(sink.practiceCards).containsExactly(card);

        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiClient, times(2)).completeWithTools(messagesCaptor.capture(), any());
        List<Map<String, Object>> secondRoundMessages = messagesCaptor.getAllValues().get(1);
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
        when(aiClient.completeWithTools(any(), any())).thenReturn(
                new CompanionAiClient.ToolCalls(List.of(
                        new CompanionAiClient.ToolCall("call-1", "start_practice", "{\"nodeCode\":\"KT12\"}"))),
                new CompanionAiClient.FinalText("practice is ready"));
        when(toolGovernor.execute(any(), any(), any())).thenReturn(
                new ToolExecutionResult(
                        "call-1",
                        "start_practice",
                        true,
                        "{\"sessionId\":901,\"questionCount\":1}",
                        card));
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(1);
            onToken.accept("practice is ready");
            return null;
        }).when(aiClient).streamMessages(any(), any());
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

        when(aiClient.completeWithTools(any(), any())).thenReturn(
                new CompanionAiClient.ToolCalls(List.of(
                        new CompanionAiClient.ToolCall("call-1", "query_mastery", "{\"nodeCode\":\"KT12\"}"))),
                new CompanionAiClient.FinalText(""));
        when(toolGovernor.execute(any(), any(), any())).thenReturn(
                new ToolExecutionResult("call-1", "query_mastery", true, "{\"nodeCode\":\"KT12\",\"effectiveScore\":42}"));
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(1);
            onToken.accept("先补");
            onToken.accept("二次函数基础");
            return null;
        }).when(aiClient).streamMessages(any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertEquals(List.of("先补", "二次函数基础"), sink.tokens);
        assertEquals(List.of("query_mastery|查询掌握度"), sink.toolStarts);
        assertEquals(List.of("query_mastery|true"), sink.toolResults);

        ArgumentCaptor<List<Map<String, Object>>> schemaCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiClient, times(2)).completeWithTools(any(), schemaCaptor.capture());
        assertThat(schemaCaptor.getAllValues().get(0)).hasSize(1);
        assertThat(schemaCaptor.getAllValues().get(0).get(0))
                .containsEntry("type", "function");

        ArgumentCaptor<ToolContext> ctxCaptor = ArgumentCaptor.forClass(ToolContext.class);
        ArgumentCaptor<CompanionAiClient.ToolCall> callCaptor = ArgumentCaptor.forClass(CompanionAiClient.ToolCall.class);
        verify(toolGovernor).execute(ctxCaptor.capture(), callCaptor.capture(), any(ToolExecutionState.class));
        assertEquals(1L, ctxCaptor.getValue().studentId());
        assertEquals(10L, ctxCaptor.getValue().courseId());
        assertEquals("query_mastery", callCaptor.getValue().name());

        ArgumentCaptor<List<Map<String, Object>>> streamMessagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiClient).streamMessages(streamMessagesCaptor.capture(), any());
        List<Map<String, Object>> messages = streamMessagesCaptor.getValue();
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

        ArgumentCaptor<CompanionMessage> messageCaptor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper, times(1)).insert(messageCaptor.capture());
        assertEquals("先补二次函数基础", messageCaptor.getValue().getContent());
    }

    @Test
    void streamReplyWhenMaxRoundsExhaustedStillStreamsFinalReply() {
        Long conversationId = 302L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("继续")));

        configureTools(true, 1, 3, List.of(fakeTool(
                "query_mastery",
                "查询掌握度",
                Map.of("type", "object", "properties", Map.of()))));

        when(aiClient.completeWithTools(any(), any())).thenReturn(
                new CompanionAiClient.ToolCalls(List.of(
                        new CompanionAiClient.ToolCall("call-1", "query_mastery", "{}"))));
        when(toolGovernor.execute(any(), any(), any())).thenReturn(
                new ToolExecutionResult("call-1", "query_mastery", true, "{\"items\":[]}"));
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(1);
            onToken.accept("继续回答");
            return null;
        }).when(aiClient).streamMessages(any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        service.streamReply(conversationId, token -> { });

        verify(aiClient, times(1)).completeWithTools(any(), any());
        ArgumentCaptor<List<Map<String, Object>>> streamMessagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiClient).streamMessages(streamMessagesCaptor.capture(), any());
        List<Map<String, Object>> messages = streamMessagesCaptor.getValue();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(2)).containsEntry("role", "assistant");
        assertThat(messages.get(3)).containsEntry("role", "tool").containsEntry("content", "{\"items\":[]}");
    }

    @Test
    void streamReplyWhenToolFailsAppendsToolErrorAndStillStreamsFinalReply() {
        Long conversationId = 303L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("给我建议")));

        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery",
                "查询掌握度",
                Map.of("type", "object", "properties", Map.of()))));

        when(aiClient.completeWithTools(any(), any())).thenReturn(
                new CompanionAiClient.ToolCalls(List.of(
                        new CompanionAiClient.ToolCall("call-err", "query_mastery", "{}"))),
                new CompanionAiClient.FinalText(""));
        when(toolGovernor.execute(any(), any(), any())).thenReturn(
                new ToolExecutionResult("call-err", "query_mastery", false,
                        "{\"error\":\"tool_error\",\"message\":\"boom\"}"));
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(1);
            onToken.accept("先从概念开始");
            return null;
        }).when(aiClient).streamMessages(any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertEquals(List.of("query_mastery|false"), sink.toolResults);
        ArgumentCaptor<List<Map<String, Object>>> streamMessagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiClient).streamMessages(streamMessagesCaptor.capture(), any());
        List<Map<String, Object>> messages = streamMessagesCaptor.getValue();
        assertThat(messages.get(messages.size() - 1))
                .containsEntry("role", "tool")
                .containsEntry("tool_call_id", "call-err")
                .containsEntry("content", "{\"error\":\"tool_error\",\"message\":\"boom\"}");

        ArgumentCaptor<CompanionMessage> messageCaptor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertEquals("先从概念开始", messageCaptor.getValue().getContent());
    }

    @Test
    void streamReplyWhenFirstToolResolutionFailsFallsBackToLegacyStreamAndPersistsReply() {
        Long conversationId = 312L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("need fallback")));
        stubWeakPoint();
        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery",
                "query mastery",
                Map.of("type", "object", "properties", Map.of()))));

        when(aiClient.completeWithTools(any(), any())).thenThrow(new IllegalStateException("stage one boom"));
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
        verify(aiClient).completeWithTools(any(), any());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiClient).stream(promptCaptor.capture(), any(), any(), any());
        assertThat(promptCaptor.getValue()).doesNotContain("## 工具使用");
        assertThat(promptCaptor.getValue()).contains("## 学生薄弱点");
        verify(aiClient, never()).streamMessages(any(), any());
        verify(toolGovernor, never()).execute(any(), any(), any());

        ArgumentCaptor<CompanionMessage> messageCaptor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertEquals("legacy fallback reply", messageCaptor.getValue().getContent());
    }

    @Test
    void streamReplyWhenLaterToolResolutionFailsStreamsWithAccumulatedToolContext() {
        Long conversationId = 313L;
        stubConversationAndPrompt(conversationId, List.of(userMessage("need evidence fallback")));
        configureTools(true, 2, 3, List.of(fakeTool(
                "query_mastery",
                "query mastery",
                Map.of("type", "object", "properties", Map.of()))));

        when(aiClient.completeWithTools(any(), any())).thenReturn(
                new CompanionAiClient.ToolCalls(List.of(
                        new CompanionAiClient.ToolCall("call-1", "query_mastery", "{\"nodeCode\":\"KT12\"}"))))
                .thenThrow(new IllegalStateException("second round boom"));
        when(toolGovernor.execute(any(), any(), any())).thenReturn(
                new ToolExecutionResult("call-1", "query_mastery", true,
                        "{\"nodeCode\":\"KT12\",\"effectiveScore\":42}"));
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(1);
            onToken.accept("fallback uses tool context");
            return null;
        }).when(aiClient).streamMessages(any(), any());
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        RecordingSink sink = new RecordingSink();
        service.streamReply(conversationId, sink);

        assertEquals(List.of("fallback uses tool context"), sink.tokens);
        verify(aiClient, times(2)).completeWithTools(any(), any());
        verify(aiClient, never()).stream(any(), any(), any(), any());

        ArgumentCaptor<List<Map<String, Object>>> streamMessagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiClient).streamMessages(streamMessagesCaptor.capture(), any());
        List<Map<String, Object>> messages = streamMessagesCaptor.getValue();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(2)).containsEntry("role", "assistant").containsKey("tool_calls");
        assertThat(messages.get(3))
                .containsEntry("role", "tool")
                .containsEntry("tool_call_id", "call-1")
                .containsEntry("content", "{\"nodeCode\":\"KT12\",\"effectiveScore\":42}");

        ArgumentCaptor<CompanionMessage> messageCaptor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertEquals("fallback uses tool context", messageCaptor.getValue().getContent());
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
        assertThat(prompt).contains("当前学习路径");
        assertThat(prompt).contains("当前焦点节点");
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
        CompanionToolProperties properties = new CompanionToolProperties();
        properties.setEnabled(enabled);
        properties.setMaxRounds(maxRounds);
        properties.setMaxToolCalls(maxToolCalls);
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
        when(messageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(history);
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(whitelistNodes);
        when(pathService.getCurrent(1L, 10L)).thenReturn(path());
        when(diagnosticResultService.getResult(1L, 10L)).thenReturn(null);
        when(courseMapper.selectById(10L)).thenReturn(null);
        when(nodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(focusNode);
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

    private static final class RecordingSink extends CompanionService.CompanionStreamSink {
        private final List<String> tokens = new ArrayList<>();
        private final List<String> toolStarts = new ArrayList<>();
        private final List<String> toolResults = new ArrayList<>();
        private final List<PracticeStartVO> practiceCards = new ArrayList<>();
        private final List<String> events = new ArrayList<>();

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
        public void practiceCard(PracticeStartVO card) {
            practiceCards.add(card);
            events.add("practice_card|" + (card == null ? "null" : card.getSessionId()));
        }
    }
}
