package com.wenjin.dto;

import lombok.Data;

/**
 * 题库种子导入结果摘要。
 *   · imported ：成功落库的题目数；
 *   · skipped  ：因题干在该课程下已存在而跳过的题目数。
 */
@Data
public class ImportBankResult {

    /** 成功导入题目数 */
    private int imported;

    /** 题干已存在被跳过题目数 */
    private int skipped;

    /** 是否使用了 AI 清洗 */
    private boolean aiCleaned;
}
