package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 诊断试卷单题视图（学生侧，绝不含答案/是否正确等敏感字段）。
 * OptionVO 嵌套类同样不含任何正误信息，确保下行 JSON 无答案泄露。
 */
@Data
public class PaperQuestionVO {

    /** 题目 ID */
    private Long questionId;

    /** 题干 */
    private String stem;

    /** 所属章节（来自主知识点的 chapter，缺失时为"未分类"） */
    private String chapter;

    /** 题型：1=单选, 2=多选, 3=判断, 4=简答 */
    private Integer type;

    /** 选项列表（仅含标识与文本，无正误信息） */
    private List<OptionVO> options;

    /** 单个选项（只暴露 key + text，不含 isCorrect / pointNodeCode）。 */
    @Data
    public static class OptionVO {

        /** 选项标识（A/B/C/D） */
        private String key;

        /** 选项文本 */
        private String text;
    }
}
