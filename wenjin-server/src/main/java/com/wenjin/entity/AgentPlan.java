package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学习计划（agent_plan，M10）。
 * <p>status: 0=DRAFT,1=ACTIVE,2=DONE,3=CANCELLED；goal_type: 0=EXAM_PREP。
 */
@Data
@TableName("agent_plan")
public class AgentPlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long studentId;

    private Long courseId;

    /** 0=EXAM_PREP */
    private Integer goalType;

    private LocalDate targetDate;

    /** 0=DRAFT,1=ACTIVE,2=DONE,3=CANCELLED */
    private Integer status;

    /** 限定节点集 JSON；空=全图谱；可含 at_risk 等标志 */
    private String scopeJson;

    private Long createdRunId;

    private LocalDateTime createdAt;

    private LocalDateTime confirmedAt;
}
