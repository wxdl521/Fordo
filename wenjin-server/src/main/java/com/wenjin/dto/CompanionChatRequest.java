package com.wenjin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 学习伴侣对话请求。
 */
@Data
public class CompanionChatRequest {

    /** 学生 ID（可选兼容字段，服务端以 principal 为准） */
    private Long studentId;

    /** 课程 ID */
    @NotNull(message = "courseId 不能为空")
    @Positive(message = "courseId 必须为正数")
    private Long courseId;

    /** 会话 ID（首次为 null，后续复用） */
    private Long conversationId;

    /** 发起时的图谱节点上下文（可空） */
    private String nodeCode;

    /** 用户消息 */
    @NotBlank(message = "消息不能为空")
    @Size(min = 1, max = 4000, message = "消息长度须在 1-4000 之间")
    private String message;

    /**
     * 可选：本轮要复盘的练习会话（学生点击「复盘错题」时携带）。控制器校验其属于当前学生与课程，
     * 否则 404；只对本轮有效，作为 {@code get_recent_mistakes} 的默认会话。
     */
    @Positive(message = "reviewPracticeSessionId 必须为正数")
    private Long reviewPracticeSessionId;
}
