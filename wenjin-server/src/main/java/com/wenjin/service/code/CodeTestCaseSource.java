package com.wenjin.service.code;

import java.util.List;

/**
 * 读取编程题测试用例（由题库侧实现，读 {@code question_code_case}）。
 * 题目不存在、未审核通过或不是编程题时返回空列表。
 */
public interface CodeTestCaseSource {

    List<CodeTestCase> casesFor(long questionId);
}
