package com.wenjin.dto;

import java.util.List;

/**
 * 近期错题 + 误区节点 top 汇总（命中次数降序，最多 3 个）。
 */
public record MistakeReport(List<MistakeVO> mistakes, List<MisconceptionSummary> topMisconceptions) {

    public MistakeReport {
        mistakes = mistakes == null ? List.of() : List.copyOf(mistakes);
        topMisconceptions = topMisconceptions == null ? List.of() : List.copyOf(topMisconceptions);
    }

    public static MistakeReport empty() {
        return new MistakeReport(List.of(), List.of());
    }
}
