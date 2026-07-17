package com.wenjin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 作业对外视图（派发 202 与进度查询共用）。
 */
@Data
public class AiJobVO {

    private Long id;
    private String kind;
    private Long courseId;
    private String requestKey;
    /** 0=PENDING,1=RUNNING,2=SUCCEEDED,3=FAILED,4=CANCELLED */
    private Integer status;
    private Integer progress;
    private Integer attempts;
    private String errorCode;
    /** 成功时解析后的结果对象；失败/进行中为 null */
    private JsonNode result;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
