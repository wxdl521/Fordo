package com.wenjin.dto;

import lombok.Data;

/**
 * 课程题库按审核状态分组计数（GROUP BY status）。
 */
@Data
public class QuestionStatusCount {
    /** 审核状态：0=待审 / 1=通过 / 2=驳回 */
    private Integer status;
    /** 该状态下的题目数 */
    private Long count;
}
