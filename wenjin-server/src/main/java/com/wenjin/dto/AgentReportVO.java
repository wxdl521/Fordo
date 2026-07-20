package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 教师端多智能体报告视图。
 */
@Data
public class AgentReportVO {

    private Long id;
    private Long courseId;
    /** 0=TEACHER_WEEKLY, 1=SUPERVISOR_SAMPLE */
    private Integer kind;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    /** 0=PENDING,1=READY,2=FAILED */
    private Integer status;
    /** 解析后的 payload；PENDING 可为 null */
    private JsonNode payload;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
