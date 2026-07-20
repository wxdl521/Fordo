package com.wenjin.dto;

import lombok.Data;

import java.util.List;

@Data
public class DraftPlanResultVO {
    private Long planId;
    private int days;
    private int taskCount;
    private String explain;
    private String errorCode;
    private List<String> suggestedScope;
}
