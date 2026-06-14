package com.wenjin.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * AI 出题返回的单道单选题（出题即自带标注）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiQuestion {

    /** 题干 */
    private String stem;

    /** 选项列表（恰一个 correct=true） */
    private List<AiDistractor> options;

    /** 解析 */
    private String analysis;

    /** 难度 1–5 */
    private Integer difficulty;

    /** 主考点 node_code（=出题目标节点） */
    private String mainPoint;

    /** 次考点 node_code 列表（⊆ 白名单） */
    private List<String> subPoints;
}
