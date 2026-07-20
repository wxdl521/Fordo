package com.wenjin.ai;

/**
 * 会话摘要 LLM 润色（M7 SUMMARY_REFRESH）。
 * <p>请求路径仍只做确定性 mergeSummary；本接口仅由异步作业调用。
 */
public interface SummaryRefreshAiClient {

    /**
     * @param existingSummary 当前 summary_text（可能已为确定性折叠结果）
     * @param foldedSnippet   本批折叠进摘要的原始片段（可空）
     * @return 润色后的摘要正文；null/blank 表示跳过写回
     */
    String refresh(String existingSummary, String foldedSnippet);
}
