package com.wenjin.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class PlanTaskVO {
    private Long id;
    private LocalDate taskDate;
    private String nodeCode;
    private String taskKind;
    private Integer status;
    private Integer origin;
}
