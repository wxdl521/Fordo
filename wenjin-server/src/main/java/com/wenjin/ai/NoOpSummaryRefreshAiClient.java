package com.wenjin.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 默认空润色：跳过写回，保留请求路径确定性摘要。
 * <p>用 name 条件避免与自身候选类型循环导致 bean 缺失。
 */
@Component
@ConditionalOnMissingBean(name = "openAiCompatibleSummaryRefreshAiClient")
public class NoOpSummaryRefreshAiClient implements SummaryRefreshAiClient {

    @Override
    public String refresh(String existingSummary, String foldedSnippet) {
        return null;
    }
}
