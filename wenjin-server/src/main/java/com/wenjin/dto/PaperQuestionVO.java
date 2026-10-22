package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    /** 题型：1=单选, 2=多选, 3=判断, 4=简答, 5=编程 */
    private Integer type;

    /** 选项列表（仅含标识与文本，无正误信息） */
    private List<OptionVO> options;

    /** 编程题起始代码（仅 type=5 且有起始代码时出现；非编程题 JSON 中省略） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String starterCode;

    /** 编程题语言（仅 type=5 时出现，v1 恒为 java） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String codeLanguage;

    /**
     * 编程题样例（仅 type=5 时出现）：只含<b>非 hidden</b> 用例的输入与期望输出；
     * hidden 用例的任何内容都不下发。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<SampleVO> sampleCases;

    /** 编程题样例（学生可见的非 hidden 用例）。 */
    @Data
    public static class SampleVO {

        /** 样例序号（沿用用例 seq） */
        private Integer seq;

        /** 样例输入 */
        private String stdin;

        /** 样例期望输出 */
        private String expectedStdout;
    }

    /** 单个选项（只暴露 key + text，不含 isCorrect / pointNodeCode）。 */
    @Data
    public static class OptionVO {

        /** 选项标识（A/B/C/D） */
        private String key;

        /** 选项文本 */
        private String text;
    }
}
