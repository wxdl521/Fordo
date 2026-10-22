package com.wenjin.controller;

import com.wenjin.audit.Audited;
import com.wenjin.common.BusinessException;
import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AuthContextInterceptor;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.InsightCard;
import com.wenjin.dto.TeacherAssistantChatRequest;
import com.wenjin.security.CourseAccessService;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.PrincipalLoader;
import com.wenjin.security.TokenService;
import com.wenjin.security.UserRole;
import com.wenjin.service.CompanionService.CompanionStreamSink;
import com.wenjin.service.assistant.TeacherAssistantService;
import com.wenjin.service.companion.ToolContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TeacherAssistantControllerTest {

    private static final long TEACHER = 10L;
    private static final long STUDENT = 2L;

    private final TokenService tokenService = new TokenService("test-secret", 3600);
    private TeacherAssistantService service;
    private CourseAccessService courseAccess;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TeacherAssistantService.class);
        courseAccess = mock(CourseAccessService.class);
        // 课程归属校验依赖 CurrentUser（教师角色）；模拟真实语义：非教师 → 403
        doAnswer(inv -> {
            CurrentUser.requireTeacher();
            return null;
        }).when(courseAccess).assertManageCourse(any());
        PrincipalLoader loader = mock(PrincipalLoader.class);
        when(loader.load(anyLong(), anyLong())).thenAnswer(inv -> {
            long uid = inv.getArgument(0);
            return Optional.of(new CurrentPrincipal(uid, uid == TEACHER ? UserRole.TEACHER : UserRole.STUDENT));
        });
        when(loader.load(anyLong())).thenAnswer(inv -> {
            long uid = inv.getArgument(0);
            return Optional.of(new CurrentPrincipal(uid, uid == TEACHER ? UserRole.TEACHER : UserRole.STUDENT));
        });
        mvc = MockMvcBuilders.standaloneSetup(new TeacherAssistantController(service, courseAccess))
                .addInterceptors(new AuthContextInterceptor(tokenService, loader))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    private String bearer(long uid) {
        return "Bearer " + tokenService.issue(uid, uid == TEACHER ? 1 : 2);
    }

    @Test
    @DisplayName("chat：meta → tool_start → tool_result → insight_card → token → done；工作线程持有完整教师主体；ToolContext 为老师上下文")
    void chat_streamsEventsInOrder_withTeacherPrincipalOnWorker() throws Exception {
        when(service.startTurn(TEACHER, 5L, null, "谁需要关注")).thenReturn(88L);
        AtomicReference<CurrentPrincipal> workerPrincipal = new AtomicReference<>();
        AtomicReference<ToolContext> ctxSeen = new AtomicReference<>();
        doAnswer(inv -> {
            workerPrincipal.set(CurrentUser.getPrincipal());
            ctxSeen.set(inv.getArgument(2));
            CompanionStreamSink sink = inv.getArgument(3);
            sink.toolStart("list_students_needing_attention", "正在查找需关注学生…");
            sink.toolResult("list_students_needing_attention", true);
            sink.insightCard(new InsightCard("需关注学生", List.of("学生", "平均掌握"),
                    List.of(List.of("张三", "38")), "按关注分排序"));
            sink.token("张三需要关注。");
            return null;
        }).when(service).streamReply(eq(TEACHER), eq(88L), any(ToolContext.class), any(CompanionStreamSink.class));

        MvcResult result = mvc.perform(post("/api/teacher/assistant/chat")
                        .header("Authorization", bearer(TEACHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"courseId\":5,\"message\":\"谁需要关注\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult();

        String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(idx(stream, "meta")).isLessThan(idx(stream, "tool_start"));
        assertThat(idx(stream, "tool_start")).isLessThan(idx(stream, "tool_result"));
        assertThat(idx(stream, "tool_result")).isLessThan(idx(stream, "insight_card"));
        assertThat(idx(stream, "insight_card")).isLessThan(idx(stream, "token"));
        assertThat(idx(stream, "token")).isLessThan(idx(stream, "done"));
        assertThat(stream).contains("\"conversationId\":88").contains("\"title\":\"需关注学生\"")
                .contains("\"columns\":[\"学生\",\"平均掌握\"]").contains("\"rows\":[[\"张三\",\"38\"]]")
                .contains("\"t\":\"张三需要关注。\"");
        verify(service).assertEnabled();
        verify(courseAccess).assertManageCourse(5L);
        assertThat(workerPrincipal.get()).isNotNull();
        assertThat(workerPrincipal.get().userId()).isEqualTo(TEACHER);
        assertThat(workerPrincipal.get().role()).isEqualTo(UserRole.TEACHER);
        assertThat(ctxSeen.get().isTeacher()).isTrue();
        assertThat(ctxSeen.get().courseId()).isEqualTo(5L);
        assertThat(ctxSeen.get().actorUserId()).isEqualTo(TEACHER);
        assertThat(ctxSeen.get().studentId()).isNull();
    }

    @Test
    @DisplayName("chat：服务异常 → error 事件")
    void chat_errorEvent() throws Exception {
        when(service.startTurn(anyLong(), anyLong(), any(), anyString())).thenReturn(88L);
        doThrow(new IllegalStateException("boom")).when(service)
                .streamReply(anyLong(), anyLong(), any(), any());
        MvcResult result = mvc.perform(post("/api/teacher/assistant/chat")
                        .header("Authorization", bearer(TEACHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":5,\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult();
        String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream).contains("event:error");
        assertThat(stream).doesNotContain("event:done");
    }

    @Test
    @DisplayName("学生调用 → 403，不建会话")
    void student_forbidden() throws Exception {
        mvc.perform(post("/api/teacher/assistant/chat")
                        .header("Authorization", bearer(STUDENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":5,\"message\":\"hi\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/teacher/assistant/conversations").param("courseId", "5")
                        .header("Authorization", bearer(STUDENT)))
                .andExpect(status().isForbidden());
        verify(service, never()).startTurn(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("非本人课程 → 404，不建会话")
    void foreignCourse_404() throws Exception {
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "资源不存在")).when(courseAccess).assertManageCourse(7L);
        mvc.perform(post("/api/teacher/assistant/chat")
                        .header("Authorization", bearer(TEACHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":7,\"message\":\"hi\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/teacher/assistant/conversations").param("courseId", "7")
                        .header("Authorization", bearer(TEACHER)))
                .andExpect(status().isNotFound());
        verify(service, never()).startTurn(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("他人会话 → 404（chat / 消息 / 删除）")
    void foreignConversation_404() throws Exception {
        when(service.startTurn(TEACHER, 5L, 99L, "hi"))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "会话不存在"));
        when(service.getMessages(eq(TEACHER), eq(99L), isNull(), isNull()))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "会话不存在"));
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "会话不存在")).when(service).deleteConversation(TEACHER, 99L);

        mvc.perform(post("/api/teacher/assistant/chat")
                        .header("Authorization", bearer(TEACHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":5,\"conversationId\":99,\"message\":\"hi\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/teacher/assistant/conversations/99/messages").header("Authorization", bearer(TEACHER)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/teacher/assistant/conversations/99").header("Authorization", bearer(TEACHER)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("开关关闭 → 全部接口 404")
    void flagOff_404() throws Exception {
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "AI 助教功能未开启")).when(service).assertEnabled();
        mvc.perform(post("/api/teacher/assistant/chat")
                        .header("Authorization", bearer(TEACHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":5,\"message\":\"hi\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/teacher/assistant/conversations").param("courseId", "5")
                        .header("Authorization", bearer(TEACHER)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/teacher/assistant/conversations/1/messages").header("Authorization", bearer(TEACHER)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/teacher/assistant/conversations/1").header("Authorization", bearer(TEACHER)))
                .andExpect(status().isNotFound());
        verify(service, never()).startTurn(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("会话列表 / 消息 / 删除：按当前老师 id 调服务")
    void conversationEndpoints_delegateWithCurrentTeacher() throws Exception {
        mvc.perform(get("/api/teacher/assistant/conversations").param("courseId", "5")
                        .header("Authorization", bearer(TEACHER)))
                .andExpect(status().isOk());
        verify(service).listConversations(TEACHER, 5L, null, null);
        mvc.perform(get("/api/teacher/assistant/conversations/3/messages").header("Authorization", bearer(TEACHER)))
                .andExpect(status().isOk());
        verify(service).getMessages(TEACHER, 3L, null, null);
        mvc.perform(delete("/api/teacher/assistant/conversations/3").header("Authorization", bearer(TEACHER)))
                .andExpect(status().isOk());
        verify(service).deleteConversation(TEACHER, 3L);
    }

    @Test
    @DisplayName("chat 带 @Audited(TEACHER_ASSISTANT_CHAT)，审计 target_id = courseId（不记内容）")
    void chat_isAudited() throws Exception {
        Audited audited = TeacherAssistantController.class
                .getMethod("chat", TeacherAssistantChatRequest.class, HttpServletRequest.class)
                .getAnnotation(Audited.class);
        assertThat(audited).isNotNull();
        assertThat(audited.action()).isEqualTo("TEACHER_ASSISTANT_CHAT");
        assertThat(audited.target()).isEqualTo("COURSE");
    }

    private static int idx(String stream, String event) {
        int a = stream.indexOf("event:" + event);
        int b = stream.indexOf("event: " + event);
        int i = a < 0 ? b : (b < 0 ? a : Math.min(a, b));
        assertThat(i).as("event " + event).isGreaterThanOrEqualTo(0);
        return i;
    }
}
