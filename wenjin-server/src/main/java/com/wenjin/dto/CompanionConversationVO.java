package com.wenjin.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 学习伴侣会话 VO。
 */
@Data
public class CompanionConversationVO {

    private Long id;

    /** 会话标题 */
    private String title;

    /** 发起时的节点上下文 */
    private String nodeCode;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
