package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionCodeCase;
import com.wenjin.mapper.QuestionCodeCaseMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.service.code.CodeTestCase;
import com.wenjin.service.code.CodeTestCaseSource;
import com.wenjin.support.QuestionStatus;
import com.wenjin.support.QuestionType;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link CodeTestCaseSource} 的数据库实现：仅对 APPROVED 且 type=CODE 的题返回用例（按 seq 升序）。
 */
@Service
public class DbCodeTestCaseSource implements CodeTestCaseSource {

    private final QuestionMapper questionMapper;
    private final QuestionCodeCaseMapper caseMapper;

    public DbCodeTestCaseSource(QuestionMapper questionMapper, QuestionCodeCaseMapper caseMapper) {
        this.questionMapper = questionMapper;
        this.caseMapper = caseMapper;
    }

    @Override
    public List<CodeTestCase> casesFor(long questionId) {
        Question q = questionMapper.selectById(questionId);
        if (q == null || q.getType() == null || q.getType() != QuestionType.CODE
                || q.getStatus() == null || q.getStatus() != QuestionStatus.APPROVED) {
            return List.of();
        }
        List<QuestionCodeCase> rows = caseMapper.selectList(
                new LambdaQueryWrapper<QuestionCodeCase>()
                        .eq(QuestionCodeCase::getQuestionId, questionId)
                        .orderByAsc(QuestionCodeCase::getSeq));
        return rows.stream()
                .map(r -> new CodeTestCase(
                        r.getSeq() == null ? 0 : r.getSeq(),
                        r.getStdin() == null ? "" : r.getStdin(),
                        r.getExpectedStdout() == null ? "" : r.getExpectedStdout(),
                        r.getHidden() != null && r.getHidden() == 1))
                .toList();
    }
}
