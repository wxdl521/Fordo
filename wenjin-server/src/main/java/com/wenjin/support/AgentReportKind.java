package com.wenjin.support;

/**
 * agent_report.kind 取值。
 * <p>0=TEACHER_WEEKLY, 1=SUPERVISOR_SAMPLE
 */
public final class AgentReportKind {

    public static final int TEACHER_WEEKLY = 0;
    public static final int SUPERVISOR_SAMPLE = 1;

    private AgentReportKind() {}

    public static boolean isValid(int kind) {
        return kind == TEACHER_WEEKLY || kind == SUPERVISOR_SAMPLE;
    }
}
