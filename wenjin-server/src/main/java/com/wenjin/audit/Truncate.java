package com.wenjin.audit;

/** 日志列宽截断工具：防止超长输入把 INSERT 撑爆（MySQL 严格模式下超长会直接报错）。 */
public final class Truncate {

    private Truncate() {}

    /** 截断到 {@code max} 个字符；null 原样返回。 */
    public static String to(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
