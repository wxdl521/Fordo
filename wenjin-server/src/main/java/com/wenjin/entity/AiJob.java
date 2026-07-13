package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 异步写作业（ai_job 表）。
 * <p>status: 0=PENDING, 1=RUNNING, 2=SUCCEEDED, 3=FAILED, 4=CANCELLED。
 */
@Data
@TableName("ai_job")
public class AiJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 作业类型（见 {@link com.wenjin.support.AiJobKind}） */
    private String kind;

    /** 发起教师 userId（逻辑外键→sys_user.id） */
    private Long ownerId;

    /** 所属课程（逻辑外键→course.id） */
    private Long courseId;

    /** 幂等键（Idempotency-Key 或客户端生成） */
    private String requestKey;

    /** 0=PENDING,1=RUNNING,2=SUCCEEDED,3=FAILED,4=CANCELLED */
    private Integer status;

    /** 进度 0–100 */
    private Integer progress;

    /** 领取次数（含恢复后重领） */
    private Integer attempts;

    /** 请求载荷 JSON（含文件 hash/size 等可控元数据） */
    private String requestJson;

    /** 成功结果 JSON */
    private String resultJson;

    /** 稳定错误码（不落堆栈/敏感明细） */
    private String errorCode;

    /** Durable cooperative cancellation request. */
    private LocalDateTime cancelRequestedAt;

    /** UUID identifying the worker attempt that currently owns this row. */
    private String leaseToken;

    /** Time at which the current worker attempt becomes recoverable. */
    private LocalDateTime leaseUntil;

    /** Most recent successful lease heartbeat. */
    private LocalDateTime heartbeatAt;

    /** Set before entering code that can produce domain side effects. */
    private LocalDateTime effectStartedAt;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
