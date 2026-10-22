package com.wenjin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 教师 AI 助教对话请求（POST /api/teacher/assistant/chat）。
 * 实际长度上限另由 {@code wenjin.limits.teacher-assistant.max-input-chars} 约束（超出 413）。
 */
@Data
public class TeacherAssistantChatRequest {

    @NotNull(message = "courseId 不能为空")
    @Positive(message = "courseId 必须为正数")
    private Long courseId;

    /** 会话 ID（首次为 null） */
    @Positive(message = "conversationId 必须为正数")
    private Long conversationId;

    @NotBlank(message = "消息不能为空")
    @Size(min = 1, max = 4000, message = "消息长度须在 1-4000 之间")
    private String message;
}
