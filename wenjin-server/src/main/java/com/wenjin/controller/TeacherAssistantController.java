package com.wenjin.controller;

import com.wenjin.audit.AuditInterceptor;
import com.wenjin.audit.Audited;
import com.wenjin.common.BusinessException;
import com.wenjin.common.Result;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.CompanionMessageVO;
import com.wenjin.dto.InsightCard;
import com.wenjin.dto.TeacherAssistantChatRequest;
import com.wenjin.dto.TeacherConversationVO;
import com.wenjin.security.CourseAccessService;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.service.CompanionService.CompanionStreamSink;
import com.wenjin.service.assistant.TeacherAssistantService;
import com.wenjin.service.companion.ToolContext;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 教师 AI 助教（方向 7）。开关 {@code wenjin.features.teacher-assistant-enabled=false} 时全部 404。
 *
 * <p>鉴权全部在请求线程完成：教师/管理员角色、{@code assertManageCourse(courseId)}、会话归属（他人会话 404），
 * 之后才进入 SSE 工作线程；工作线程设置完整主体（含角色），工具只拿到已授权课程的 {@link ToolContext#forTeacher}。
 *
 * <p>SSE 事件：{@code meta{conversationId}} → ({@code tool_start{name,label}} → {@code tool_result{name,ok}}
 * → [{@code insight_card{title,columns,rows,note}}])* ↔ {@code token{t}}… → {@code done{}} | {@code error{message}}。
 */
@RestController
@RequestMapping("/api/teacher/assistant")
@Validated
public class TeacherAssistantController {

    /** 非业务异常对外只给通用提示，避免泄露内部细节（堆栈/SQL/上游报错）。 */
    private static final String GENERIC_ERROR = "助教暂时不可用，请稍后重试";

    private static final Logger log = LoggerFactory.getLogger(TeacherAssistantController.class);

    private final TeacherAssistantService assistantService;
    private final CourseAccessService courseAccessService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TeacherAssistantController(TeacherAssistantService assistantService,
                                      CourseAccessService courseAccessService) {
        this.assistantService = assistantService;
        this.courseAccessService = courseAccessService;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * POST /api/teacher/assistant/chat  body {@code {courseId, conversationId?, message}}（SSE）。
     * 审计只记动作与课程 id，不记消息内容。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Audited(action = "TEACHER_ASSISTANT_CHAT", target = "COURSE")
    public SseEmitter chat(@Valid @RequestBody TeacherAssistantChatRequest req, HttpServletRequest httpRequest) {
        httpRequest.setAttribute(AuditInterceptor.TARGET_ID_ATTRIBUTE, String.valueOf(req.getCourseId()));
        assistantService.assertEnabled();
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        long courseId = req.getCourseId();
        courseAccessService.assertManageCourse(courseId);
        long teacherId = principal.userId();
        // 会话归属 / 长度校验与用户消息落库都在请求线程：失败直接返回 HTTP 404/413
        Long conversationId = assistantService.startTurn(teacherId, courseId, req.getConversationId(), req.getMessage());
        ToolContext toolContext = ToolContext.forTeacher(teacherId, courseId);

        SseEmitter emitter = new SseEmitter(180_000L);
        executor.execute(() -> {
            // ThreadLocal 不跨线程：在工作线程设置完整主体（含角色），finally 清理
            CurrentUser.set(principal);
            try {
                emitter.send(SseEmitter.event().name("meta").data(Map.of("conversationId", conversationId)));
                assistantService.streamReply(teacherId, conversationId, toolContext, sink(emitter));
                emitter.send(SseEmitter.event().name("done").data(Map.of()));
                emitter.complete();
            } catch (Exception e) {
                log.error("Teacher assistant chat error", e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", e instanceof BusinessException && e.getMessage() != null
                                    ? e.getMessage() : GENERIC_ERROR)));
                } catch (Exception sendError) {
                    log.debug("Failed to send error event via SSE: {}", sendError.toString());
                }
                emitter.complete();
            } finally {
                CurrentUser.clear();
            }
        });
        return emitter;
    }

    /** GET /api/teacher/assistant/conversations?courseId=&beforeId=&limit= （仅本人会话） */
    @GetMapping("/conversations")
    public Result<List<TeacherConversationVO>> listConversations(
            @RequestParam("courseId") @Positive Long courseId,
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        assistantService.assertEnabled();
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        courseAccessService.assertManageCourse(courseId);
        return Result.ok(assistantService.listConversations(principal.userId(), courseId, beforeId, limit));
    }

    /** GET /api/teacher/assistant/conversations/{id}/messages?beforeId=&limit= （他人会话 404） */
    @GetMapping("/conversations/{id}/messages")
    public Result<List<CompanionMessageVO>> getMessages(
            @PathVariable("id") @Positive Long id,
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        assistantService.assertEnabled();
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        courseAccessService.assertManageCourse(assistantService.requireOwnedCourseId(principal.userId(), id));
        return Result.ok(assistantService.getMessages(principal.userId(), id, beforeId, limit));
    }

    /** DELETE /api/teacher/assistant/conversations/{id} （他人会话 404） */
    @DeleteMapping("/conversations/{id}")
    public Result<Void> deleteConversation(@PathVariable("id") @Positive Long id) {
        assistantService.assertEnabled();
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        courseAccessService.assertManageCourse(assistantService.requireOwnedCourseId(principal.userId(), id));
        assistantService.deleteConversation(principal.userId(), id);
        return Result.ok(null);
    }

    private static CompanionStreamSink sink(SseEmitter emitter) {
        return new CompanionStreamSink() {
            @Override
            public void token(String token) {
                send(emitter, "token", Map.of("t", token));
            }

            @Override
            public void toolStart(String name, String label) {
                send(emitter, "tool_start", Map.of("name", name, "label", label == null ? name : label));
            }

            @Override
            public void toolResult(String name, boolean ok) {
                send(emitter, "tool_result", Map.of("name", name, "ok", ok));
            }

            @Override
            public void insightCard(InsightCard card) {
                if (card != null) {
                    send(emitter, "insight_card", card);
                }
            }
        };
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            throw new RuntimeException("Failed to send " + event, e);
        }
    }
}
