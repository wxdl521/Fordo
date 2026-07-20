package com.wenjin.support.multiagent;

/**
 * 周报叙述段生成（可选；失败/NoOp 时报告仍 READY、narrative 空）。
 */
public interface ReportNarrativeGenerator {

    /**
     * @param structuredSummary 已聚合的结构化摘要文本
     * @return 叙述段；空串或 null 均可
     */
    String generate(String structuredSummary);

    /** 默认 NoOp：始终返回空串，不调 LLM。 */
    final class NoOp implements ReportNarrativeGenerator {
        @Override
        public String generate(String structuredSummary) {
            return "";
        }
    }
}
