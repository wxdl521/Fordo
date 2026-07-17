package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 执行轨迹 run（agent_run 表）。
 * <p>status: 0=RUNNING, 1=SUCCEEDED, 2=FAILED, 3=DEGRADED, 4=CANCELLED。
 */
@Data
@TableName("agent_run")
public class AgentRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 RequestIdFilter 的 requestId */
    private String requestId;

    private Long studentId;

    private Long courseId;

    private Long conversationId;

    /** COMPANION_CHAT / DIAGNOSTIC / PLAN_GENERATE / PLAN_REPLAN / ... */
    private String kind;

    /** 0=RUNNING,1=SUCCEEDED,2=FAILED,3=DEGRADED,4=CANCELLED */
    private Integer status;

    private String model;

    /** 首条输入前 200 字，非全文 */
    private String inputDigest;

    private Integer tokensIn;

    private Integer tokensOut;

    /** 1=按 chars/2 估算 */
    private Integer tokensEstimated;

    private Integer durationMs;

    private String errorCode;

    /** M10 转向/取消使用，本期只建列 */
    private LocalDateTime cancelRequestedAt;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
