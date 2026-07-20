package com.wenjin.support;

/**
 * companion_conversation.summary_origin 稳定取值。
 * <p>请求路径只写 {@link #DETERMINISTIC}（mergeSummary）；异步
 * {@link AiJobKind#SUMMARY_REFRESH} 写 {@link #LLM_REFRESH}。
 */
public final class SummaryOrigin {

    /** 请求路径确定性折叠（ConversationContextBuilder.mergeSummary） */
    public static final int DETERMINISTIC = 0;

    /** 异步 SUMMARY_REFRESH LLM 润色 */
    public static final int LLM_REFRESH = 1;

    private SummaryOrigin() {}
}
