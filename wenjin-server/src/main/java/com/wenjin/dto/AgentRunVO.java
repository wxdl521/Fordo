package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教师端 Agent 执行轨迹 run 列表项。
 */
@Data
public class AgentRunVO {

    private Long id;
    private String kind;
    /** 0=RUNNING,1=SUCCEEDED,2=FAILED,3=DEGRADED,4=CANCELLED */
    private Integer status;
    private String model;
    private Integer tokensIn;
    private Integer tokensOut;
    private Integer durationMs;
    private String errorCode;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishedAt;

    /** 关联 agent_step 条数 */
    private Integer stepCount;
}
