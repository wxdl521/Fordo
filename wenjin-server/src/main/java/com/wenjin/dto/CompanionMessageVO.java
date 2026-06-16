package com.wenjin.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 学习伴侣消息 VO。
 */
@Data
public class CompanionMessageVO {

    /** 角色：user / ai */
    private String role;

    /** 消息内容 */
    private String content;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
