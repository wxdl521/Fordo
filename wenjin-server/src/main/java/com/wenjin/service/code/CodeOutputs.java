package com.wenjin.service.code;

/**
 * 输出比对与展示的纯函数。
 */
final class CodeOutputs {

    private CodeOutputs() {
    }

    /** 截断后的预览文本。 */
    record Preview(String text, boolean truncated) {
    }

    /**
     * 比对规则（契约）：逐行去掉行尾空白、去掉末尾空行后精确相等。{@code \r\n} 视同 {@code \n}
     * （{@code \r} 是行尾空白）。期望为 null 视为空串；实际为 null（没有拿到输出）一律不通过。
     */
    static boolean outputsMatch(String expected, String actual) {
        return actual != null && normalize(expected).equals(normalize(actual));
    }

    static String normalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String[] lines = s.split("\n", -1);
        int end = lines.length;
        for (int i = 0; i < lines.length; i++) {
            lines[i] = lines[i].stripTrailing();
        }
        while (end > 0 && lines[end - 1].isEmpty()) {
            end--;
        }
        return String.join("\n", java.util.Arrays.asList(lines).subList(0, end));
    }

    /** 截到 {@code maxChars} 个 char，不劈开代理对。 */
    static Preview preview(String s, int maxChars) {
        if (s == null) {
            return new Preview("", false);
        }
        int max = Math.max(0, maxChars);
        if (s.length() <= max) {
            return new Preview(s, false);
        }
        int cut = max;
        if (cut > 0 && Character.isHighSurrogate(s.charAt(cut - 1))) {
            cut--;
        }
        return new Preview(s.substring(0, cut), true);
    }

    /** 编译器输出里的容器内路径对学生无意义，去掉。 */
    static String cleanCompileOutput(String s) {
        return s == null ? "" : s.replace("/src/Main.java", "Main.java").replace("/work/Main.java", "Main.java");
    }

    static boolean looksLikeOutOfMemory(String stderr) {
        return stderr != null && stderr.contains("java.lang.OutOfMemoryError");
    }
}
