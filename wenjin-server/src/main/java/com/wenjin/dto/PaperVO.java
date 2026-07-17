package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 入口诊断试卷视图（学生侧）。
 * total 与 questions.size() 保持一致；所有子对象均不含答案。
 */
@Data
public class PaperVO {

    /** 课程 ID */
    private Long courseId;

    /**
     * 冻结会话令牌（UUID）。交卷必须原样回传；空卷时可为空。
     */
    private String sessionToken;

    /** 实际题数（= questions.size()） */
    private int total;

    /** 题目列表（按分层抽样顺序排列） */
    private List<PaperQuestionVO> questions;
}
