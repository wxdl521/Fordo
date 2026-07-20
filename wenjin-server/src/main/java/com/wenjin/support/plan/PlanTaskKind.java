package com.wenjin.support.plan;

/** agent_plan_task.task_kind：0=REVIEW,1=LEARN,2=PRACTICE。 */
public final class PlanTaskKind {
    public static final int REVIEW = 0;
    public static final int LEARN = 1;
    public static final int PRACTICE = 2;

    private PlanTaskKind() {}

    public static String nameOf(int code) {
        return switch (code) {
            case REVIEW -> "REVIEW";
            case LEARN -> "LEARN";
            case PRACTICE -> "PRACTICE";
            default -> "UNKNOWN";
        };
    }
}
