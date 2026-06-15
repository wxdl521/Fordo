package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 掌握度快照实体（mastery_snapshot 表，只增不改，成长曲线数据来源）。
 */
@Data
@TableName("mastery_snapshot")
public class MasterySnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学生（逻辑外键→sys_user.id） */
    private Long studentId;

    /** 课程（逻辑外键→course.id） */
    private Long courseId;

    /** 知识点（逻辑外键→kg_node.id） */
    private Long nodeId;

    /** 当时的掌握度分值（0–100） */
    private BigDecimal masteryScore;

    /** 当时的掌握等级 */
    private Integer masteryLevel;

    /** 快照时间 */
    private LocalDateTime snapshotAt;
}
