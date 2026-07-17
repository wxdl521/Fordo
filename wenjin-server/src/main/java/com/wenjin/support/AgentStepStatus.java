package com.wenjin.support;

/**
 * agent_step.status 取值。
 * <p>0=OK, 1=FAILED, 2=SKIPPED, 3=CACHED
 */
public final class AgentStepStatus {

    public static final int OK = 0;
    public static final int FAILED = 1;
    public static final int SKIPPED = 2;
    public static final int CACHED = 3;

    private AgentStepStatus() {}
}
