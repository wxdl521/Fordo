package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.config.AccessGuard;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.CompanionChatRequest;
import com.wenjin.dto.CompanionConversationVO;
import com.wenjin.dto.CompanionMessageVO;
import com.wenjin.service.CompanionService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
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
 * AI 学习伴侣接口（PRD 9.2）：SSE 流式对话 + 会话查询。
 */
@RestController
@RequestMapping("/api/companion")
public class CompanionController {

    private static final Logger log = LoggerFactory.getLogger(CompanionController.class);

    private final CompanionService companionService;
    // 虚拟线程：每次 SSE 请求独立一个虚拟线程，避免 newCachedThreadPool 在高并发下无界扩张平台线程
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CompanionController(CompanionService companionService) {
        this.companionService = companionService;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate in time, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("Executor shutdown interrupted", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 流式对话端点（SSE）。
     * POST /api/companion/chat
     *
     * 事件流：meta{conversationId} → token{t} ... → done{} | error{message}
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody CompanionChatRequest req) {
        // 必须在请求线程（此处）校验——CurrentUser 是 ThreadLocal，进入 executor 后不可见
        AccessGuard.assertSelf(req.getStudentId());
        SseEmitter emitter = new SseEmitter(180_000L); // 3 minutes timeout

        // 在请求线程取出身份，透传给虚拟线程（ThreadLocal 不跨线程）
        final Long uid = CurrentUser.get();

        executor.execute(() -> {
            // 把请求线程的身份注入当前虚拟线程；finally 保证清理（虚拟线程每任务新建，清理是好习惯）
            CurrentUser.set(uid);
            try {
                // 1. 开始一轮对话，获取 conversationId
                Long conversationId = companionService.startTurn(req);

                // 2. 发送 meta 事件（包含 conversationId）
                emitter.send(SseEmitter.event()
                        .name("meta")
                        .data(Map.of("conversationId", conversationId)));

                // 3. 流式生成回复，逐 token 发送
                companionService.streamReply(conversationId, token -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("token")
                                .data(Map.of("t", token)));
                    } catch (Exception e) {
                        log.error("Failed to send token via SSE", e);
                        throw new RuntimeException("Failed to send token", e);
                    }
                });

                // 4. 完成，发送 done 事件
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(Map.of()));
                emitter.complete();

            } catch (Exception e) {
                log.error("Companion chat error", e);
                try {
                    // 发送 error 事件
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("message", e.getMessage() != null ? e.getMessage() : "Unknown error")));
                } catch (Exception sendError) {
                    log.error("Failed to send error event via SSE", sendError);
                }
                emitter.complete(); // 用 complete() 而非 completeWithError()，避免触发全局异常处理
            } finally {
                CurrentUser.clear();
            }
        });

        return emitter;
    }

    /**
     * 查询学生的所有会话（按更新时间倒序）。
     * GET /api/companion/conversations?studentId=&courseId=
     */
    @GetMapping("/conversations")
    public Result<List<CompanionConversationVO>> listConversations(
            @RequestParam("studentId") Long studentId,
            @RequestParam("courseId") Long courseId) {
        AccessGuard.assertSelf(studentId);
        return Result.ok(companionService.listConversations(studentId, courseId));
    }

    /**
     * 查询会话内的所有消息（按创建时间正序）。
     * GET /api/companion/conversations/{id}
     */
    @GetMapping("/conversations/{id}")
    public Result<List<CompanionMessageVO>> getMessages(@PathVariable("id") Long id) {
        return Result.ok(companionService.getMessages(id));
    }

    /**
     * 删除会话（含所有消息）。
     * DELETE /api/companion/conversations/{id}
     */
    @DeleteMapping("/conversations/{id}")
    public Result<Void> deleteConversation(@PathVariable("id") Long id) {
        companionService.deleteConversation(id);
        return Result.ok(null);
    }
}
