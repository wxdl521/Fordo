package com.wenjin.support;

/**
 * 图谱边的关系类型枚举。统一口径（PRD §3.3），中文标签 <-> 数据库 TINYINT 编码互转。
 */
public enum RelationType {

    /** 学完 A 才能学 B —— 路径推荐与拓扑排序的核心依据 */
    PREREQUISITE(1, "前置"),
    /** 章节包含知识点 —— 结构组织 */
    CONTAINS(2, "包含"),
    /** 易混淆 / 相关联 —— 辅助提示 */
    RELATED(3, "相关"),
    /** 知识点对应典型题型 —— 题目关联 */
    APPLIES(4, "应用");

    private final int code;
    private final String label;

    RelationType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 中文标签 -> 枚举；未知标签返回 null */
    public static RelationType fromLabel(String label) {
        if (label == null) {
            return null;
        }
        String trimmed = label.trim();
        for (RelationType t : values()) {
            if (t.label.equals(trimmed)) {
                return t;
            }
        }
        return null;
    }

    /** 数据库编码 -> 中文标签；未知编码返回原编码字符串 */
    public static String labelOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (RelationType t : values()) {
            if (t.code == code) {
                return t.label;
            }
        }
        return String.valueOf(code);
    }
}
