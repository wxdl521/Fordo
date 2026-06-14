package com.wenjin.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * AI 对存量题的标注结果。超纲时 mainPoint=null，并在 reason 中说明。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiAnnotation {

    /** 主考点 node_code；超纲（不在白名单/课程范围内）时为 null */
    private String mainPoint;

    /** 次考点 node_code 列表 */
    private List<String> subPoints;

    /** 干扰项→考点映射（每项 optionKey + pointNodeCode） */
    private List<AiDistractor> distractors;

    /** 标注理由；超纲时此处说明原因 */
    private String reason;
}
