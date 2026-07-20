package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 多智能体产出报告（agent_report，M11 可选）。
 * <p>kind: 0=TEACHER_WEEKLY,1=SUPERVISOR_SAMPLE；
 * status: 0=PENDING,1=READY,2=FAILED。
 */
@Data
@TableName("agent_report")
public class AgentReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courseId;

    /** 0=TEACHER_WEEKLY, 1=SUPERVISOR_SAMPLE */
    private Integer kind;

    private LocalDate periodStart;

    private LocalDate periodEnd;

    /** 0=PENDING,1=READY,2=FAILED */
    private Integer status;

    /** 结构化 payload JSON */
    private String payloadJson;

    private LocalDateTime createdAt;
}
