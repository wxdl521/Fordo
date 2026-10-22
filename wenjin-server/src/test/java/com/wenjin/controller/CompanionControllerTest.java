package com.wenjin.controller;

import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AuthContextInterceptor;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.TestPrincipalSupport;
import com.wenjin.dto.CompanionChatRequest;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.security.TokenService;
import com.wenjin.service.CompanionService;
import com.wenjin.service.CourseService;
import com.wenjin.service.MistakeQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanionControllerTest {

    @Mock
    private CompanionService companionService;

    @Mock
    private CourseService courseService;

    @Mock
    private MistakeQueryService mistakeQueryService;

    private final TokenService tokenService = new TokenService("test-secret", 3600);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CompanionController controller = new CompanionController(companionService, courseService, mistakeQueryService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(TestPrincipalSupport.authInterceptor(tokenService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearCurrentUser() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("chat: emits meta, tool events, practice card, token, and done in order")
    void chat_streamsStructuredEventsInOrder() throws Exception {
        PracticeStartVO card = practiceCard();
        when(companionService.startTurn(any(CompanionChatRequest.class))).thenReturn(321L);
        doAnswer(invocation -> {
            CompanionService.CompanionStreamSink sink = invocation.getArgument(1);
            sink.toolStart("query_mastery", "正在查询你的掌握情况…");
            sink.toolResult("query_mastery", true);
            sink.practiceCard(card);
            sink.toolStart("run_code_check", "正在运行你的代码…");
            sink.toolResult("run_code_check", true);
            sink.codeResult(new com.wenjin.dto.CodeResultVO("RUN", "COMPILE_ERROR", null, null,
                    "Main.java:3: error", "", "", java.util.List.of(), 12L, null));
            sink.token("hello");
            sink.token(" world");
            return null;
        }).when(companionService).streamReply(anyLong(), any(CompanionService.CompanionStreamSink.class));

        MvcResult result = mvc.perform(post("/api/companion/chat")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {
                                  "studentId": 2,
                                  "courseId": 5,
                                  "conversationId": 99,
                                  "nodeCode": "KT12",
                                  "message": "help me practice"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult();

        String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(eventIndex(stream, "meta")).isLessThan(eventIndex(stream, "tool_start"));
        assertThat(eventIndex(stream, "tool_start")).isLessThan(eventIndex(stream, "tool_result"));
        assertThat(eventIndex(stream, "tool_result")).isLessThan(eventIndex(stream, "practice_card"));
        assertThat(eventIndex(stream, "practice_card")).isLessThan(eventIndex(stream, "token"));
        assertThat(Math.max(stream.lastIndexOf("event:tool_result"), stream.lastIndexOf("event: tool_result")))
                .isLessThan(eventIndex(stream, "code_result"));
        assertThat(eventIndex(stream, "code_result")).isLessThan(eventIndex(stream, "token"));
        assertThat(stream).contains("\"mode\":\"RUN\"").contains("\"verdict\":\"COMPILE_ERROR\"")
                .contains("\"compileError\":\"Main.java:3: error\"").contains("\"durationMs\":12");
        assertThat(eventIndex(stream, "token")).isLessThan(eventIndex(stream, "done"));
        assertThat(stream).contains("\"conversationId\":321");
        assertThat(stream).contains("\"name\":\"query_mastery\"");
        assertThat(stream).contains("\"label\":\"正在查询你的掌握情况…\"");
        assertThat(stream).contains("\"ok\":true");
        assertThat(stream).contains("\"sessionId\":901");
        assertThat(stream).contains("\"nodeId\":12");
        assertThat(stream).contains("\"nodeCode\":\"KT12\"");
        assertThat(stream).contains("\"name\":\"Quadratic Functions\"");
        assertThat(stream).contains("\"questionId\":700");
        assertThat(stream).contains("\"stem\":\"What shape is the graph?\"");
        assertThat(stream).contains("\"t\":\"hello\"");
        assertThat(stream).contains("\"t\":\" world\"");

        ArgumentCaptor<CompanionChatRequest> requestCaptor = ArgumentCaptor.forClass(CompanionChatRequest.class);
        verify(companionService).startTurn(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getStudentId()).isEqualTo(2L);
        assertThat(requestCaptor.getValue().getCourseId()).isEqualTo(5L);
        assertThat(requestCaptor.getValue().getConversationId()).isEqualTo(99L);
        assertThat(requestCaptor.getValue().getNodeCode()).isEqualTo("KT12");
        assertThat(requestCaptor.getValue().getMessage()).isEqualTo("help me practice");
        verify(courseService).assertAccessibleByStudent(2L, 5L);
        verify(companionService).streamReply(eq(321L), any(CompanionService.CompanionStreamSink.class));
    }

    @Test
    @DisplayName("chat: citations event carries {valid, invalid}, comes after tokens and before done")
    void chat_citationsEventPrecedesDone() throws Exception {
        when(companionService.startTurn(any(CompanionChatRequest.class))).thenReturn(322L);
        doAnswer(invocation -> {
            CompanionService.CompanionStreamSink sink = invocation.getArgument(1);
            sink.token("见资料 #501，也见资料 #999");
            sink.citations(java.util.List.of(501L), java.util.List.of(999L));
            return null;
        }).when(companionService).streamReply(anyLong(), any(CompanionService.CompanionStreamSink.class));

        MvcResult result = mvc.perform(post("/api/companion/chat")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"studentId": 2, "courseId": 5, "message": "讲讲用例图"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult();

        String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(eventIndex(stream, "token")).isLessThan(eventIndex(stream, "citations"));
        assertThat(eventIndex(stream, "citations")).isLessThan(eventIndex(stream, "done"));
        assertThat(stream).contains("\"valid\":[501]").contains("\"invalid\":[999]");
    }

    @Test
    @DisplayName("chat: no citations callback -> no citations event (kill switch / no references)")
    void chat_withoutCitations_emitsNoCitationsEvent() throws Exception {
        when(companionService.startTurn(any(CompanionChatRequest.class))).thenReturn(323L);
        doAnswer(invocation -> {
            CompanionService.CompanionStreamSink sink = invocation.getArgument(1);
            sink.token("hello");
            return null;
        }).when(companionService).streamReply(anyLong(), any(CompanionService.CompanionStreamSink.class));

        MvcResult result = mvc.perform(post("/api/companion/chat")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"studentId": 2, "courseId": 5, "message": "hi"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult();

        String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream).doesNotContain("event:citations");
        assertThat(stream).contains("event:done");
    }

    @Test
    @DisplayName("chat: reviewPracticeSessionId not owned by student/course → 404 before async work")
    void chat_reviewSessionNotOwned_returns404WithoutStartingTurn() throws Exception {
        org.mockito.Mockito.doThrow(new com.wenjin.common.BusinessException(ResultCode.NOT_FOUND, "练习会话不存在"))
                .when(mistakeQueryService).assertPracticeSessionOwned(2L, 5L, 77L);

        mvc.perform(post("/api/companion/chat")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "studentId": 2,
                                  "courseId": 5,
                                  "message": "帮我复盘一下刚才那次练习的错题",
                                  "reviewPracticeSessionId": 77
                                }
                                """))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.getCode()));

        verify(mistakeQueryService).assertPracticeSessionOwned(2L, 5L, 77L);
        verify(companionService, never()).startTurn(any());
    }

    @Test
    @DisplayName("chat: non-positive reviewPracticeSessionId → 400")
    void chat_reviewSessionNonPositive_returns400() throws Exception {
        mvc.perform(post("/api/companion/chat")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courseId": 5, "message": "复盘", "reviewPracticeSessionId": 0}
                                """))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isBadRequest());

        verify(companionService, never()).startTurn(any());
    }

    @Test
    @DisplayName("chat: owned reviewPracticeSessionId is passed to streamReply for this turn only")
    void chat_ownedReviewSession_passedToStreamReply() throws Exception {
        when(companionService.startTurn(any(CompanionChatRequest.class))).thenReturn(321L);

        MvcResult result = mvc.perform(post("/api/companion/chat")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"courseId": 5, "message": "帮我复盘一下刚才那次练习的错题", "reviewPracticeSessionId": 77}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult();

        verify(mistakeQueryService).assertPracticeSessionOwned(2L, 5L, 77L);
        verify(companionService).streamReply(eq(321L), eq(77L), any(CompanionService.CompanionStreamSink.class));
    }

    @Test
    @DisplayName("chat: mismatched studentId returns 403 before async work and does not start turn")
    void chat_mismatchedStudent_returns403WithoutStartingTurn() throws Exception {
        mvc.perform(post("/api/companion/chat")
                        .header("Authorization", "Bearer " + tokenService.issue(9L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "studentId": 2,
                                  "courseId": 5,
                                  "message": "help me practice"
                                }
                                """))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));

        verify(companionService, never()).startTurn(any());
        verify(companionService, never()).streamReply(any(), any(CompanionService.CompanionStreamSink.class));
    }

    @Test
    @DisplayName("chat: inaccessible course returns 403 before async work and does not start turn")
    void chat_inaccessibleCourse_returns403WithoutStartingTurn() throws Exception {
        org.mockito.Mockito.doThrow(new com.wenjin.common.BusinessException(ResultCode.FORBIDDEN, "course denied"))
                .when(courseService).assertAccessibleByStudent(2L, 5L);

        mvc.perform(post("/api/companion/chat")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "studentId": 2,
                                  "courseId": 5,
                                  "message": "help me practice"
                                }
                                """))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));

        verify(courseService).assertAccessibleByStudent(2L, 5L);
        verify(companionService, never()).startTurn(any());
        verify(companionService, never()).streamReply(any(), any(CompanionService.CompanionStreamSink.class));
    }

    @Test
    @DisplayName("chat: missing token returns 401 before async work and does not start turn")
    void chat_missingToken_returns401WithoutStartingTurn() throws Exception {
        mvc.perform(post("/api/companion/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "studentId": 2,
                                  "courseId": 5,
                                  "message": "help me practice"
                                }
                                """))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));

        verify(companionService, never()).startTurn(any());
        verify(companionService, never()).streamReply(any(), any(CompanionService.CompanionStreamSink.class));
    }

    @Test
    @DisplayName("chat: stream failure is emitted as error event")
    void chat_streamFailure_emitsErrorEvent() throws Exception {
        when(companionService.startTurn(any(CompanionChatRequest.class))).thenReturn(321L);
        doAnswer(invocation -> {
            throw new IllegalStateException("boom");
        }).when(companionService).streamReply(anyLong(), any(CompanionService.CompanionStreamSink.class));

        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/companion/chat")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {
                                  "studentId": 2,
                                  "courseId": 5,
                                  "message": "help me practice"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult();

        String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream).contains("event:error");
        assertThat(stream).contains("\"message\":\"boom\"");
        assertThat(stream).doesNotContain("event:done");
    }

    @Test
    @DisplayName("conversations: inaccessible course returns 403 and does not list conversations")
    void listConversations_inaccessibleCourse_returns403WithoutListing() throws Exception {
        org.mockito.Mockito.doThrow(new com.wenjin.common.BusinessException(ResultCode.FORBIDDEN, "course denied"))
                .when(courseService).assertAccessibleByStudent(2L, 5L);

        mvc.perform(MockMvcRequestBuilders.get("/api/companion/conversations")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .param("studentId", "2")
                        .param("courseId", "5"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));

        verify(courseService).assertAccessibleByStudent(2L, 5L);
        verify(companionService, never()).listConversations(anyLong(), anyLong());
    }

    private static int eventIndex(String stream, String eventName) {
        int index = stream.indexOf("event:" + eventName);
        if (index >= 0) {
            return index;
        }
        return stream.indexOf("event: " + eventName);
    }

    private static PracticeStartVO practiceCard() {
        PracticeStartVO card = new PracticeStartVO();
        card.setSessionId(901L);

        PracticeStartVO.NodeRef nodeRef = new PracticeStartVO.NodeRef();
        nodeRef.setNodeId(12L);
        nodeRef.setNodeCode("KT12");
        nodeRef.setName("Quadratic Functions");
        card.setNode(nodeRef);

        PaperQuestionVO question = new PaperQuestionVO();
        question.setQuestionId(700L);
        question.setStem("What shape is the graph?");
        PaperQuestionVO.OptionVO option = new PaperQuestionVO.OptionVO();
        option.setKey("A");
        option.setText("Parabola");
        question.setOptions(List.of(option));
        card.setQuestions(List.of(question));
        return card;
    }
}
