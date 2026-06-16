package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 伴侣会话内一条消息（companion_message 表）。 */
@Data
@TableName("companion_message")
public class CompanionMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话（逻辑外键→companion_conversation.id） */
    private Long conversationId;

    /** 角色：1=user, 2=ai */
    private Integer role;

    /** 消息正文 */
    private String content;

    private LocalDateTime createdAt;
}
