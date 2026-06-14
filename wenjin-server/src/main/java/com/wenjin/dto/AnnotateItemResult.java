package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 单道存量题的标注结果。
 *   · mainPoint  ：主考点 node_code；超纲或考点超出白名单时为 null；
 *   · subPoints  ：次考点 node_code 列表（透传 AI 标注）；
 *   · reason     ：标注/未标注理由（超纲时透传 AI 说明）；
 *   · persisted  ：是否已落库（超纲不强标，未落库为 false）。
 */
@Data
public class AnnotateItemResult {

    /** 题干 */
    private String stem;

    /** 主考点 node_code（可空：超纲为 null） */
    private String mainPoint;

    /** 次考点 node_code 列表 */
    private List<String> subPoints;

    /** 标注/未标注理由 */
    private String reason;

    /** 是否已落库 */
    private boolean persisted;
}
