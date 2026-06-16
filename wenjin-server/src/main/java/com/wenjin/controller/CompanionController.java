package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.CompanionChatRequest;
import com.wenjin.dto.CompanionConversationVO;
import com.wenjin.dto.CompanionMessageVO;
import com.wenjin.service.CompanionService;
import org.springframework.http.MediaType;
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

/**
 * AI 学习伴侣接口（PRD 9.2）：SSE 流式对话 + 会话查询。
 */
@RestController
@RequestMapping("/api/companion")
public class CompanionController {

    private final CompanionService companionService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public CompanionController(CompanionService companionService) {
        this.companionService = companionService;
    }

    /**
     * 流式对话端点（SSE）。
     * POST /api/companion/chat
     *
     * 事件流：meta{conversationId} → token{t} ... → done{} | error{message}
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody CompanionChatRequest req) {
        SseEmitter emitter = new SseEmitter(180_000L); // 3 minutes timeout

        executor.execute(() -> {
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
                        throw new RuntimeException("Failed to send token", e);
                    }
                });

                // 4. 完成，发送 done 事件
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(Map.of()));
                emitter.complete();

            } catch (Exception e) {
                try {
                    // 发送 error 事件
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("message", e.getMessage() != null ? e.getMessage() : "Unknown error")));
                } catch (Exception ignored) {
                    // If sending error event fails, just complete with error
                }
                emitter.completeWithError(e);
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
}
