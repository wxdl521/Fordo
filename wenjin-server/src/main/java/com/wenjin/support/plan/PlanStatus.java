package com.wenjin.support.plan;

/** agent_plan.status：0=DRAFT,1=ACTIVE,2=DONE,3=CANCELLED。 */
public final class PlanStatus {
    public static final int DRAFT = 0;
    public static final int ACTIVE = 1;
    public static final int DONE = 2;
    public static final int CANCELLED = 3;

    private PlanStatus() {}
}
