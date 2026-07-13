package com.wenjin.support;

/**
 * ai_job.error_code 稳定取值（仅写码，不写异常明细）。
 */
public final class AiJobErrorCodes {

    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String AI_ERROR = "AI_ERROR";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String CANCELLED = "CANCELLED";
    public static final String STALE_EXHAUSTED = "STALE_EXHAUSTED";
    public static final String UNKNOWN_OUTCOME = "UNKNOWN_OUTCOME";
    public static final String MAX_ATTEMPTS = "MAX_ATTEMPTS";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    private AiJobErrorCodes() {}
}
