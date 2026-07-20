package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 计划每日任务（agent_plan_task，M10）。
 * <p>task_kind: 0=REVIEW,1=LEARN,2=PRACTICE；
 * status: 0=PENDING,1=DONE,2=SKIPPED,3=RESCHEDULED；
 * origin: 0=INITIAL,1=REPLAN。
 */
@Data
@TableName("agent_plan_task")
public class AgentPlanTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long planId;

    private LocalDate taskDate;

    private String nodeCode;

    /** 0=REVIEW,1=LEARN,2=PRACTICE */
    private Integer taskKind;

    /** 0=PENDING,1=DONE,2=SKIPPED,3=RESCHEDULED */
    private Integer status;

    /** 0=INITIAL,1=REPLAN */
    private Integer origin;
}
