package com.wenjin.dto;

import lombok.Data;

/**
 * 题库种子导入结果摘要。
 *   · imported          ：成功落库的题目数；
 *   · skipped           ：被跳过的题目总数（题干已存在 + nodeCode 非法）；
 *   · invalidNodeSkipped：其中因 nodeCode 不在课程图谱内而跳过的题目数（skipped 的子集）。
 */
@Data
public class ImportBankResult {

    /** 成功导入题目数 */
    private int imported;

    /** 被跳过题目总数（题干已存在 + nodeCode 非法） */
    private int skipped;

    /** 因 nodeCode 不在课程图谱内被跳过的题目数（skipped 的子集，便于前端/答辩展示） */
    private int invalidNodeSkipped;

    /** 是否使用了 AI 清洗 */
    private boolean aiCleaned;
}
