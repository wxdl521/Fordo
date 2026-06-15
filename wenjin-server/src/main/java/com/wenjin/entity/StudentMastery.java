package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学生掌握度实体（student_mastery 表，按 学生×课程×知识点 维度；染色地图直接读 mastery_level）。
 */
@Data
@TableName("student_mastery")
public class StudentMastery {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学生（逻辑外键→sys_user.id） */
    private Long studentId;

    /** 课程（逻辑外键→course.id） */
    private Long courseId;

    /** 知识点（逻辑外键→kg_node.id） */
    private Long nodeId;

    /** 掌握度分值（0–100） */
    private BigDecimal masteryScore;

    /** 掌握等级：2=已掌握, 1=薄弱, 0=未学 */
    private Integer masteryLevel;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;
}
