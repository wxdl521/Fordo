package com.wenjin.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 出题流水线结果摘要。
 *   · generated  ：成功落库的题目数；
 *   · dropped    ：代码侧校验未通过被丢弃的题目数；
 *   · duplicated ：因题干重复（同课程已存在或本批内重复）被跳过的题目数；
 *   · questionIds：成功落库的 question.id 列表；
 *   · message    ：人类可读的计数摘要。
 */
@Data
public class GenerateResult {

    /** 成功落库题目数 */
    private int generated;

    /** 校验未通过被丢弃题目数 */
    private int dropped;

    /** 题干重复被跳过题目数 */
    private int duplicated;

    /** 成功落库的题目主键 */
    private List<Long> questionIds = new ArrayList<>();

    /** 计数摘要 */
    private String message;
}
