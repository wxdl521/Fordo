package com.wenjin.support;

/**
 * 题目审核状态常量【阶段二补充】。
 */
public final class QuestionStatus {
    private QuestionStatus() {}

    /** 待审核 */
    public static final int PENDING = 0;
    /** 已通过 */
    public static final int APPROVED = 1;
    /** 已驳回 */
    public static final int REJECTED = 2;
}
