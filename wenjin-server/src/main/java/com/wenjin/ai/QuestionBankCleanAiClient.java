package com.wenjin.ai;

import com.wenjin.dto.QuestionBankFile;
import java.util.List;

/**
 * 题库导入 AI 清洗客户端。
 * 将 Excel 解析出的原始题目发送给 AI，修正格式、标准化字段。
 */
public interface QuestionBankCleanAiClient {

    /**
     * AI 清洗原始题目列表。
     *
     * @param raw 原始题目列表
     * @return 清洗后的 QuestionBankFile（questions 列表已填充）
     */
    QuestionBankFile clean(List<QuestionBankFile.BankQuestion> raw);
}
