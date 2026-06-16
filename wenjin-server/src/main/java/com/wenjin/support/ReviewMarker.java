package com.wenjin.support;

/**
 * 图谱审核标记工具类
 * <p>
 * 用于识别和处理 relation_note 中的『待复核』前缀
 */
public class ReviewMarker {

    public static final String PREFIX = "『待复核』";

    /**
     * 判断关系描述是否标记为待复核
     */
    public static boolean isPending(String note) {
        return note != null && note.startsWith(PREFIX);
    }

    /**
     * 去除待复核前缀及紧随的中/英文冒号，返回原始原因描述
     */
    public static String strip(String note) {
        if (note == null) {
            return null;
        }
        if (note.startsWith(PREFIX)) {
            String rest = note.substring(PREFIX.length());
            if (rest.startsWith("：") || rest.startsWith(":")) {
                rest = rest.substring(1);
            }
            return rest;
        }
        return note;
    }
}
