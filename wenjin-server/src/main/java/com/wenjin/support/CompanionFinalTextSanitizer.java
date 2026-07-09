package com.wenjin.support;

/**
 * 伴侣终答文本净化：剥离模型泄漏的 DSML / tool_calls 伪协议，只保留自然语言。
 */
public final class CompanionFinalTextSanitizer {

    private CompanionFinalTextSanitizer() {}

    /**
     * 净化终答原文：去掉伪工具协议块；null/空白 → 空串；若剥离后仍像协议则返回空。
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw;

        // 整块去掉 <|DSML|tool_calls> ... </|DSML|tool_calls>（含内部参数值）
        text = text.replaceAll(
                "(?is)<\\|\\s*DSML\\s*\\|\\s*tool_calls\\s*>.*?</\\|\\s*DSML\\s*\\|\\s*tool_calls\\s*>",
                "");
        // 未闭合的 DSML tool_calls 从开标签到文末（协议尾随在正文后）
        text = text.replaceAll("(?is)<\\|\\s*DSML\\s*\\|\\s*tool_calls\\s*>.*", "");

        // 去掉其余 <|...|> / </|...|> 标签
        text = text.replaceAll("(?s)<\\|[^|>]*\\|[^>]*>", "");
        text = text.replaceAll("(?s)</\\|[^|>]*\\|[^>]*>", "");
        text = text.replaceAll("(?is)</?\\s*\\|?\\s*DSML\\s*\\|?[^>]*>", "");

        // 普通 tool_calls / invoke / parameter 伪 XML 块
        text = text.replaceAll("(?is)<\\s*tool_calls\\s*>.*?</\\s*tool_calls\\s*>", "");
        text = text.replaceAll("(?is)</?\\s*tool_calls\\s*>", "");
        text = text.replaceAll("(?is)<[^>]*invoke\\s+name\\s*=[^>]*>.*?</[^>]*invoke[^>]*>", "");
        text = text.replaceAll("(?is)<[^>]*parameter\\s+name\\s*=[^>]*>.*?</[^>]*parameter[^>]*>", "");

        // 仅含协议标点的行
        text = text.replaceAll("(?m)^\\s*[|<>/]+\\s*$", "");
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        text = text.replaceAll("\\n{3,}", "\n\n").trim();

        if (looksLikeToolProtocol(text)) {
            return "";
        }
        return text;
    }

    /**
     * 判断文本是否仍像工具伪协议（供兜底与 sanitize 末尾判定）。
     */
    public static boolean looksLikeToolProtocol(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String s = raw.toLowerCase();
        return s.contains("dsml")
                || s.contains("tool_calls")
                || s.contains("invoke name=")
                || s.contains("<|");
    }
}
