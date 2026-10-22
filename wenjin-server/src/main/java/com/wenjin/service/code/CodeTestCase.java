package com.wenjin.service.code;

/**
 * 编程题的一个测试用例（来源：表 {@code question_code_case}）。
 * 比对规则：逐行去掉行尾空白、去掉末尾空行后精确相等。
 */
public record CodeTestCase(int seq, String stdin, String expectedStdout, boolean hidden) {
}
