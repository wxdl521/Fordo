package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 存量题标注请求：一批待标注题目（题干 + 选项）。
 * 选项无需考点，标注由 AI 完成；correct 仅用于落库时确定答案。
 */
@Data
public class AnnotateRequest {

    /** 待标注题目列表 */
    private List<Item> items;

    /** 单道待标注题：题干 + 选项 */
    @Data
    public static class Item {

        /** 题干 */
        private String stem;

        /** 选项列表 */
        private List<Option> options;
    }

    /** 单个选项：标识/文本/是否正确 */
    @Data
    public static class Option {

        /** 选项标识，如 A/B/C/D */
        private String key;

        /** 选项文本 */
        private String text;

        /** 是否为正确选项 */
        private Boolean correct;
    }
}
