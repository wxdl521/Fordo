package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教师端 Agent 执行轨迹 step 明细。
 */
@Data
public class AgentStepVO {

    private Long id;
    private Long runId;
    private Integer seq;
    private String stepType;
    private String name;
    /** 0=OK,1=FAILED,2=SKIPPED,3=CACHED */
    private Integer status;
    private String inputJson;
    private String outputJson;
    private Integer tokensIn;
    private Integer tokensOut;
    private Integer durationMs;
    private String errorCode;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
