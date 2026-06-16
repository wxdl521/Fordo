package com.wenjin.dto;

import lombok.Data;

/**
 * AI 学习伴侣对话请求。
 */
@Data
public class CompanionChatRequest {

    /** 学生 ID */
    private Long studentId;

    /** 课程 ID */
    private Long courseId;

    /** 会话 ID（首次为 null，后续复用） */
    private Long conversationId;

    /** 发起时的图谱节点上下文（可空） */
    private String nodeCode;

    /** 用户消息 */
    private String message;
}
