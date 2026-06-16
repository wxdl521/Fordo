package com.wenjin.dto;

import lombok.Data;

import java.util.List;

@Data
public class QuestionReviewRequest {
    private List<Long> ids;
    private String action; // "pass" or "reject"
}
