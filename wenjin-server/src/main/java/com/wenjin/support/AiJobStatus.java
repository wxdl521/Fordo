package com.wenjin.support;

/**
 * ai_job.status 取值。
 */
public final class AiJobStatus {

    public static final int PENDING = 0;
    public static final int RUNNING = 1;
    public static final int SUCCEEDED = 2;
    public static final int FAILED = 3;
    public static final int CANCELLED = 4;

    private AiJobStatus() {}

    public static boolean isTerminal(int status) {
        return status == SUCCEEDED || status == FAILED || status == CANCELLED;
    }
}
