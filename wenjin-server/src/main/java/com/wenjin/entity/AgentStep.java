package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 执行轨迹 step（agent_step 表）。
 * <p>status: 0=OK, 1=FAILED, 2=SKIPPED, 3=CACHED。
 */
@Data
@TableName("agent_step")
public class AgentStep {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    /** run 内序号（从 1 起） */
    private Integer seq;

    /** LLM_CALL / TOOL_CALL / GUARD / DEGRADE / REFLECT / MEMORY / RETRIEVE / CITATION */
    private String stepType;

    /** 工具名或 phase1/phase2/... */
    private String name;

    /** 0=OK,1=FAILED,2=SKIPPED,3=CACHED */
    private Integer status;

    /** 超 limits.trace.max-io-chars 截断并加 [truncated] */
    private String inputJson;

    private String outputJson;

    private Integer tokensIn;

    private Integer tokensOut;

    private Integer durationMs;

    private String errorCode;

    private LocalDateTime createdAt;
}
