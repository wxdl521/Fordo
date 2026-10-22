package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 教师 AI 助教会话消息（teacher_message 表，V25）。 */
@Data
@TableName("teacher_message")
public class TeacherMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话（逻辑外键→teacher_conversation.id） */
    private Long conversationId;

    /** 角色：1=user, 2=ai */
    private Integer role;

    /** 消息正文 */
    private String content;

    private LocalDateTime createdAt;
}
