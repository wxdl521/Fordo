package com.wenjin.support;

import com.wenjin.dto.CodeCaseItem;

import java.util.List;

/**
 * 编程题入库校验（JSON 导入与教师编辑共用的纯函数）。
 */
public final class CodeQuestionRules {

    /** 单个用例 stdin / expectedStdout 字符上限。 */
    public static final int MAX_CASE_IO_CHARS = 10_000;

    private CodeQuestionRules() {
    }

    /**
     * @return 校验通过返回 {@code null}，否则返回面向教师的错误说明
     */
    public static String validate(String starterCode, List<CodeCaseItem> cases,
                                  int maxCases, int maxSourceChars) {
        if (starterCode != null && starterCode.length() > maxSourceChars) {
            return "starterCode 超过上限 " + maxSourceChars + " 字符";
        }
        if (cases == null || cases.isEmpty()) {
            return "编程题至少需要 1 个测试用例";
        }
        if (cases.size() > maxCases) {
            return "测试用例数超过上限 " + maxCases;
        }
        for (int i = 0; i < cases.size(); i++) {
            CodeCaseItem c = cases.get(i);
            if (c == null) {
                return "第 " + (i + 1) + " 个用例为空";
            }
            if (c.getExpectedStdout() == null) {
                return "第 " + (i + 1) + " 个用例缺少 expectedStdout";
            }
            if (c.getExpectedStdout().length() > MAX_CASE_IO_CHARS
                    || (c.getStdin() != null && c.getStdin().length() > MAX_CASE_IO_CHARS)) {
                return "第 " + (i + 1) + " 个用例输入/期望输出超过 " + MAX_CASE_IO_CHARS + " 字符";
            }
        }
        return null;
    }
}
