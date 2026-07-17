package com.wenjin.support;

/**
 * agent_run.status 取值。
 * <p>0=RUNNING, 1=SUCCEEDED, 2=FAILED, 3=DEGRADED, 4=CANCELLED
 */
public final class AgentRunStatus {

    public static final int RUNNING = 0;
    public static final int SUCCEEDED = 1;
    public static final int FAILED = 2;
    public static final int DEGRADED = 3;
    public static final int CANCELLED = 4;

    private AgentRunStatus() {}

    public static boolean isTerminal(int status) {
        return status == SUCCEEDED || status == FAILED || status == DEGRADED || status == CANCELLED;
    }
}
