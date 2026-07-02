package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点练习会话实体（practice_session 表，M1 练习闭环）。
 *
 * <p>question_ids 在组卷时冻结（逗号分隔），提交时只接受会话内题目，防客户端偷换题目刷分。
 * path_item_id 可空：null 表示自由练习，非 null 表示来源于学习路径步骤。
 */
@Data
@TableName("practice_session")
public class PracticeSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学生（逻辑外键→sys_user.id） */
    private Long studentId;

    /** 课程（逻辑外键→course.id） */
    private Long courseId;

    /** 目标知识点（逻辑外键→kg_node.id） */
    private Long nodeId;

    /** 来源路径步骤（可空=自由练习，逻辑外键→learning_path_item.id） */
    private Long pathItemId;

    /** 本会话题目ID列表，逗号分隔（组卷即冻结，防提交时偷换题目） */
    private String questionIds;

    /** 状态：0=进行中, 1=已提交 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime submittedAt;
}
