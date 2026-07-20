package com.wenjin.support.plan;

/** agent_plan_task.status：0=PENDING,1=DONE,2=SKIPPED,3=RESCHEDULED。 */
public final class PlanTaskStatus {
    public static final int PENDING = 0;
    public static final int DONE = 1;
    public static final int SKIPPED = 2;
    public static final int RESCHEDULED = 3;

    private PlanTaskStatus() {}
}
