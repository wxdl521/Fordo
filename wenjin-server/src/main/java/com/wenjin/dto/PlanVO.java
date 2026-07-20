package com.wenjin.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class PlanVO {
    private Long id;
    private Long courseId;
    private Integer status;
    private LocalDate targetDate;
    private String scopeJson;
    private boolean atRisk;
    private LocalDateTime createdAt;
    private LocalDateTime confirmedAt;
    private int taskCount;
    private int doneCount;
    private List<PlanTaskVO> todayTasks;
    private List<PlanTaskVO> tasks;
    private String explain;
}
