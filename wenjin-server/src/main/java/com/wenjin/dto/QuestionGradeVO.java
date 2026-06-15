package com.wenjin.dto;

import lombok.Data;

/**
 * 单题判分结果视图：告知学生该题是否正确及正确答案。
 */
@Data
public class QuestionGradeVO {

    /** 题目 ID */
    private Long questionId;

    /** 是否答对 */
    private boolean correct;

    /** 该题正确选项标识（A/B/C/D；题库无记录时为 null） */
    private String correctKey;
}
