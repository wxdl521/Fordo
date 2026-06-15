package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习路径主记录实体（learning_path 表，动态路径留痕，重算后旧路径置 status=0）。
 */
@Data
@TableName("learning_path")
public class LearningPath {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学生（逻辑外键→sys_user.id） */
    private Long studentId;

    /** 课程（逻辑外键→course.id） */
    private Long courseId;

    /** 学习目标知识点（可空=全局；本阶段=诊断卡点，逻辑外键→kg_node.id） */
    private Long targetNodeId;

    /** 状态：1=当前有效, 0=已失效 */
    private Integer status;

    private LocalDateTime generatedAt;
}
