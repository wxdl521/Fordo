package com.wenjin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 判分结果传输对象：掌握度计算的输入（题目 ID + 是否答对）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GradedAnswer {

    /** 题目 ID */
    private Long questionId;

    /** 是否答对 */
    private boolean correct;
}
