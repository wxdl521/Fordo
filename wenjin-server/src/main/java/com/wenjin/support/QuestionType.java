package com.wenjin.support;

/**
 * 题型常量（对应 question.type 字段）。
 */
public final class QuestionType {
    private QuestionType() {}

    /** 单选题 */
    public static final int SINGLE = 1;
    /** 多选题 */
    public static final int MULTI = 2;
    /** 判断题 */
    public static final int TRUE_FALSE = 3;
    /** 简答题（不自动判分） */
    public static final int SHORT_ANSWER = 4;
}
