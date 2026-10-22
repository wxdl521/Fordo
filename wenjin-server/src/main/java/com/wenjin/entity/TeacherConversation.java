package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 教师 AI 助教会话（teacher_conversation 表，V25）。归属 = teacherId。 */
@Data
@TableName("teacher_conversation")
public class TeacherConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话所有者（教师或管理员 sys_user.id） */
    private Long teacherId;

    /** 课程（创建时已校验可管理） */
    private Long courseId;

    /** 会话标题：首条问题截断 */
    private String title;

    /** 历史消息滚动摘要（送入模型；确定性 mergeSummary，不做记忆蒸馏） */
    private String summaryText;

    /** 摘要覆盖到的最后一条消息 id（含） */
    private Long summaryThroughMessageId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
