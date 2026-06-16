package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 学习伴侣会话（companion_conversation 表）。 */
@Data
@TableName("companion_conversation")
public class CompanionConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学生（逻辑外键→sys_user.id） */
    private Long studentId;

    /** 课程（逻辑外键→course.id） */
    private Long courseId;

    /** 会话标题：首条用户问题截断；从节点发起则前缀节点名 */
    private String title;

    /** 发起时的图谱节点上下文 node_code（可空） */
    private String nodeCode;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
