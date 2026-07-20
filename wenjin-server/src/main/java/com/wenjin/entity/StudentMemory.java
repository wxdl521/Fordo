package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学生长期记忆（student_memory 表，M7）。
 * <p>kind: 0=EPISODIC, 1=SEMANTIC, 2=PROCEDURAL；status: 0=ARCHIVED, 1=ACTIVE。
 */
@Data
@TableName("student_memory")
public class StudentMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long studentId;

    private Long courseId;

    /** 0=EPISODIC, 1=SEMANTIC, 2=PROCEDURAL */
    private Integer kind;

    private String content;

    /** 关联知识点 node_code（可空） */
    private String nodeCode;

    /** 来源 agent_run.id（可空） */
    private Long sourceRunId;

    /** 基础显著度（读时 effective = salience × exp(−λ·Δdays)） */
    private BigDecimal salience;

    private LocalDateTime lastReinforcedAt;

    /** 0=ARCHIVED, 1=ACTIVE */
    private Integer status;

    private LocalDateTime createdAt;
}
