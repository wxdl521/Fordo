package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 题库种子文件结构（对应 seed/问津_软件工程题库_v0.1.json）。
 * 仅映射导入所需字段，忽略未知字段。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuestionBankFile {

    /** 课程业务编码，导入时按此定位课程 */
    private String courseCode;

    /** 题目列表 */
    private List<BankQuestion> questions;

    /** 单道题库题：题干 + 知识点编码 + 选项 + 解析。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BankQuestion {

        /** 题干 */
        private String stem;

        /** 主考点 node_code */
        private String nodeCode;

        /** 所属章节（仅描述，导入不落库） */
        private String chapter;

        /** 难度 1–5（空时取默认） */
        private Integer difficulty;

        /** 解析 */
        private String analysis;

        /** 选项列表 */
        private List<BankOption> options;
    }

    /** 单个选项：标识 / 文本 / 是否正确。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BankOption {

        /** 选项标识（A/B/C/D） */
        private String key;

        /** 选项文本 */
        private String text;

        /** 是否为正确选项 */
        private Boolean correct;

        /**
         * 干扰项考点映射：该错误选项对应的误解所指向的前置知识点编码（以 KT 开头）。
         * 正确选项恒为 null；导入时若不在课程图谱白名单内会被降级为 null。
         */
        private String pointNodeCode;
    }
}
