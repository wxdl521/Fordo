package com.wenjin.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * AI 返回的单个选项（出题时的备选项，标注时的干扰项映射）。
 * 出题场景：correct 区分正确/干扰，pointNodeCode 为干扰项考点（白名单内）。
 * 标注场景：仅用 optionKey + pointNodeCode 表达“某干扰项错在哪个考点”。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiDistractor {

    /** 选项标识，如 A/B/C/D（JSON 字段名为 key） */
    @JsonProperty("key")
    private String optionKey;

    /** 选项文本（标注场景可为空） */
    private String text;

    /** 是否为正确选项（标注的干扰项映射场景无意义，可为空） */
    private Boolean correct;

    /** 该（干扰）选项对应的考点 node_code；正确项通常为 null（JSON 字段名 point_node_code） */
    @JsonProperty("point_node_code")
    private String pointNodeCode;
}
