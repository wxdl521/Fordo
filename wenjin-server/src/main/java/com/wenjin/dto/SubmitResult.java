package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 交卷判分结果：汇总得分 + 逐题正误明细。
 */
@Data
public class SubmitResult {

    /** 总题数（= answers.size()） */
    private int total;

    /** 答对题数 */
    private int correctCount;

    /** 逐题判分明细（顺序与提交顺序一致） */
    private List<QuestionGradeVO> grades;
}
