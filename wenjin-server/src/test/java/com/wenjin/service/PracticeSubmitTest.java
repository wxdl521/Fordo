package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.dto.GradedAnswer;
import com.wenjin.dto.PracticeSubmitRequest;
import com.wenjin.dto.PracticeSubmitVO;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.PracticeSession;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionOption;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.PracticeSessionMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.impl.PracticeServiceImpl;
import com.wenjin.support.QuestionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PracticeServiceImpl.submit 单元测试（TDD）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>正常提交：判分正确、落库、掌握度更新、返回 VO</li>
 *   <li>会话外题目被拒</li>
 *   <li>幂等：重复提交不写库、不调 applyAnswers</li>
 *   <li>EWMA 被以正确参数调用</li>
 *   <li>简答不进 GradedAnswer（不进 applyAnswers）</li>
 *   <li>会话不存在抛 NOT_FOUND</li>
 *   <li>会话归属不符被拒（req.studentId ≠ session.studentId）</li>
 *   <li>多选题集合相等判分 + correctAnswer 排序逗号串</li>
 *   <li>answer_record 落库字段（scene=2/sessionId）与会话置 status=1</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PracticeSubmitTest {

    @Mock QuestionNodeMapper questionNodeMapper;
    @Mock QuestionMapper questionMapper;
    @Mock AnswerRecordMapper answerRecordMapper;
    @Mock KgNodeMapper kgNodeMapper;
    @Mock QuestionOptionMapper questionOptionMapper;
    @Mock PracticeSessionMapper practiceSessionMapper;
    @Mock MasteryService masteryService;
    @Mock StudentMasteryMapper studentMasteryMapper;

    private static final Long STUDENT_ID = 2L;
    private static final Long COURSE_ID  = 1L;
    private static final Long NODE_ID    = 10L;
    private static final Long SESSION_ID = 50L;

    // ── 构造 impl（注入所有依赖） ────────────────────────────────────────────

    private PracticeServiceImpl impl() {
        PracticeServiceImpl impl = new PracticeServiceImpl(
                questionNodeMapper, questionMapper, answerRecordMapper,
                kgNodeMapper, questionOptionMapper, practiceSessionMapper,
                masteryService, studentMasteryMapper);
        ReflectionTestUtils.setField(impl, "defaultSize", 5);
        ReflectionTestUtils.setField(impl, "maxSize",     10);
        ReflectionTestUtils.setField(impl, "recencyDays", 7);
        return impl;
    }

    // ── fixture 工具方法 ──────────────────────────────────────────────────────

    private PracticeSession session(int status, String questionIds) {
        PracticeSession ps = new PracticeSession();
        ps.setId(SESSION_ID);
        ps.setStudentId(STUDENT_ID);
        ps.setCourseId(COURSE_ID);
        ps.setNodeId(NODE_ID);
        ps.setQuestionIds(questionIds);
        ps.setStatus(status);
        ps.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        return ps;
    }

    private Question question(Long id, int type) {
        Question q = new Question();
        q.setId(id);
        q.setCourseId(COURSE_ID);
        q.setStem("题干" + id);
        q.setType(type);
        q.setDifficulty(3);
        return q;
    }

    private QuestionOption correctOpt(Long questionId, String key) {
        QuestionOption o = new QuestionOption();
        o.setQuestionId(questionId);
        o.setOptionKey(key);
        o.setOptionText("选项" + key);
        o.setIsCorrect(1);
        return o;
    }

    private PracticeSubmitRequest req(Long studentId, List<PracticeSubmitRequest.AnswerItem> answers) {
        PracticeSubmitRequest r = new PracticeSubmitRequest();
        r.setStudentId(studentId);
        r.setAnswers(answers);
        return r;
    }

    private PracticeSubmitRequest.AnswerItem item(Long qid, String answer) {
        PracticeSubmitRequest.AnswerItem ai = new PracticeSubmitRequest.AnswerItem();
        ai.setQuestionId(qid);
        ai.setStudentAnswer(answer);
        return ai;
    }

    private StudentMastery mastery(double score, int level) {
        StudentMastery sm = new StudentMastery();
        sm.setStudentId(STUDENT_ID);
        sm.setCourseId(COURSE_ID);
        sm.setNodeId(NODE_ID);
        sm.setMasteryScore(BigDecimal.valueOf(score));
        sm.setMasteryLevel(level);
        sm.setUpdatedAt(LocalDateTime.now());
        return sm;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 1：正常提交——返回正确判分结果
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("正常提交：单选答对→correct=true，答错→false，返回 masteryBefore/After/Level")
    void submit_normalCase_returnsGradedResult() {
        // 两道单选题：q1 答对，q2 答错
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1,2"));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE), question(2L, QuestionType.SINGLE)));
        // q1 正确答案 A，q2 正确答案 B
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A"), correctOpt(2L, "B")));
        // 掌握度：提交前 50，提交后 55
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(50.0, 1))   // before
                .thenReturn(mastery(55.0, 1));  // after

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "A"), item(2L, "C"))));

        assertThat(vo.getGraded()).hasSize(2);
        // q1 答对
        PracticeSubmitVO.GradeItemVO g1 = vo.getGraded().get(0);
        assertThat(g1.getQuestionId()).isEqualTo(1L);
        assertThat(g1.getCorrect()).isTrue();
        assertThat(g1.getCorrectAnswer()).isEqualTo("A");
        // q2 答错
        PracticeSubmitVO.GradeItemVO g2 = vo.getGraded().get(1);
        assertThat(g2.getQuestionId()).isEqualTo(2L);
        assertThat(g2.getCorrect()).isFalse();
        assertThat(g2.getCorrectAnswer()).isEqualTo("B");
        // 掌握度字段
        assertThat(vo.getMasteryBefore()).isEqualTo(50.0);
        assertThat(vo.getMasteryAfter()).isEqualTo(55.0);
        assertThat(vo.getMasteryLevel()).isEqualTo("薄弱");
        // T5/T6 默认值
        assertThat(vo.isItemCompleted()).isFalse();
        assertThat(vo.getWeakPrerequisites()).isEmpty();
        assertThat(vo.isPathRegenerated()).isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 2：会话外题目被拒
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("提交会话外题目（questionId 不在冻结列表中）→ BusinessException")
    void submit_outsideQuestion_throwsBusinessException() {
        // 会话冻结题 1,2；提交题 1,99（99 不在）
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1,2"));

        assertThatThrownBy(() ->
                impl().submit(SESSION_ID, req(STUDENT_ID, List.of(item(1L, "A"), item(99L, "B")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("99");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 3：幂等——重复提交不写库、不调 applyAnswers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("session.status=1 时：不写 answer_record、不调 applyAnswers，重建已有结果")
    void submit_duplicateSubmit_idempotentNoRewrites() {
        // session 已提交（status=1）
        PracticeSession alreadySubmitted = session(1, "1,2");
        alreadySubmitted.setSubmittedAt(LocalDateTime.now().minusMinutes(1));
        when(practiceSessionMapper.selectById(SESSION_ID)).thenReturn(alreadySubmitted);

        // 已有 answer_record（之前写入的）
        AnswerRecord ar1 = new AnswerRecord();
        ar1.setQuestionId(1L);
        ar1.setStudentAnswer("A");
        ar1.setIsCorrect(1);
        ar1.setScene(2);
        ar1.setSessionId(SESSION_ID);

        AnswerRecord ar2 = new AnswerRecord();
        ar2.setQuestionId(2L);
        ar2.setStudentAnswer("C");
        ar2.setIsCorrect(0);
        ar2.setScene(2);
        ar2.setSessionId(SESSION_ID);

        when(answerRecordMapper.selectList(any())).thenReturn(List.of(ar1, ar2));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE), question(2L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A"), correctOpt(2L, "B")));
        when(studentMasteryMapper.selectOne(any())).thenReturn(mastery(55.0, 1));

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "A"), item(2L, "C"))));

        // 关键：不写库、不调 applyAnswers
        verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
        verify(masteryService, never()).applyAnswers(any(), any(), any());

        // 重建了 graded（2 道题）
        assertThat(vo.getGraded()).hasSize(2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 4：applyAnswers 以正确参数被调用
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("applyAnswers 被调用，传入正确的 studentId/courseId + 逐题 GradedAnswer")
    void submit_gradedAnswersPassedToApplyAnswers() {
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1,2"));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE), question(2L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A"), correctOpt(2L, "B")));
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(50.0, 1))
                .thenReturn(mastery(60.0, 2));

        impl().submit(SESSION_ID, req(STUDENT_ID, List.of(item(1L, "A"), item(2L, "B"))));

        // Capture applyAnswers arguments
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GradedAnswer>> captor = ArgumentCaptor.forClass(List.class);
        verify(masteryService).applyAnswers(
                org.mockito.ArgumentMatchers.eq(STUDENT_ID),
                org.mockito.ArgumentMatchers.eq(COURSE_ID),
                captor.capture());

        List<GradedAnswer> submitted = captor.getValue();
        assertThat(submitted).hasSize(2);
        // q1 answered "A" = correct (A is correct key)
        assertThat(submitted.get(0).getQuestionId()).isEqualTo(1L);
        assertThat(submitted.get(0).isCorrect()).isTrue();
        // q2 answered "B" = correct (B is correct key)
        assertThat(submitted.get(1).getQuestionId()).isEqualTo(2L);
        assertThat(submitted.get(1).isCorrect()).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 5：简答不进 GradedAnswer
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("简答题（type=4）：correct=null，不进 applyAnswers 的 GradedAnswer 列表")
    void submit_shortAnswerExcludedFromGradedAnswers() {
        // 一道单选（q1）+ 一道简答（q2）
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1,2"));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE), question(2L, QuestionType.SHORT_ANSWER)));
        // q1 has correct option A; q2 is SHORT_ANSWER with no correct options
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A")));
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(50.0, 1))
                .thenReturn(mastery(55.0, 1));

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "A"), item(2L, "任意简答文本"))));

        // q2 的 GradeItemVO.correct 应为 null
        PracticeSubmitVO.GradeItemVO g2 = vo.getGraded().stream()
                .filter(g -> g.getQuestionId().equals(2L))
                .findFirst().orElseThrow();
        assertThat(g2.getCorrect()).isNull();
        assertThat(g2.getCorrectAnswer()).isNull();

        // applyAnswers 只收到 q1（q2 简答排除）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GradedAnswer>> captor = ArgumentCaptor.forClass(List.class);
        verify(masteryService).applyAnswers(any(), any(), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getQuestionId()).isEqualTo(1L);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 6：会话不存在 → NOT_FOUND
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("sessionId 不存在时，抛 BusinessException（NOT_FOUND）")
    void submit_sessionNotFound_throwsBusinessException() {
        when(practiceSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        assertThatThrownBy(() ->
                impl().submit(SESSION_ID, req(STUDENT_ID, List.of())))
                .isInstanceOf(BusinessException.class);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 7：answer_record 以正确字段写入（scene=2、sessionId、isCorrect）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("answer_record 落库：scene=2、sessionId=SESSION_ID、isCorrect 正确")
    void submit_answerRecordsWrittenWithCorrectFields() {
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1"));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A")));
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(50.0, 1))
                .thenReturn(mastery(55.0, 1));

        impl().submit(SESSION_ID, req(STUDENT_ID, List.of(item(1L, "A"))));

        ArgumentCaptor<AnswerRecord> captor = ArgumentCaptor.forClass(AnswerRecord.class);
        verify(answerRecordMapper).insert(captor.capture());
        AnswerRecord saved = captor.getValue();
        assertThat(saved.getScene()).isEqualTo(2);
        assertThat(saved.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(saved.getStudentId()).isEqualTo(STUDENT_ID);
        assertThat(saved.getCourseId()).isEqualTo(COURSE_ID);
        assertThat(saved.getQuestionId()).isEqualTo(1L);
        assertThat(saved.getStudentAnswer()).isEqualTo("A");
        assertThat(saved.getIsCorrect()).isEqualTo(1);
        assertThat(saved.getAnsweredAt()).isNotNull();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 7b：会话归属不符 → BusinessException
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("req.studentId 与 session.studentId 不符 → BusinessException（会话归属校验），不落库")
    void submit_studentMismatch_throwsBusinessException() {
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1,2"));

        assertThatThrownBy(() ->
                impl().submit(SESSION_ID, req(999L, List.of(item(1L, "A")))))
                .isInstanceOf(BusinessException.class);

        // 归属不符时绝不落库、绝不更新掌握度
        verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
        verify(masteryService, never()).applyAnswers(any(), any(), any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 7c：多选题判分——集合相等 + correctAnswer 为排序逗号串
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("多选题：乱序作答 'C,A' 与正确集 {A,C} 集合相等判对；correctAnswer='A,C'")
    void submit_multiChoiceGradedSetEqual() {
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1"));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.MULTI)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A"), correctOpt(1L, "C")));
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(50.0, 1))
                .thenReturn(mastery(55.0, 1));

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "C,A"))));

        PracticeSubmitVO.GradeItemVO g = vo.getGraded().get(0);
        assertThat(g.getCorrect()).isTrue();
        assertThat(g.getCorrectAnswer()).isEqualTo("A,C");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 8：session.status 被置 1、submittedAt 被设置
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("提交后：session.status=1、submittedAt 非空，practiceSessionMapper.updateById 被调用")
    void submit_sessionMarkedSubmitted() {
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1"));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A")));
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(50.0, 1))
                .thenReturn(mastery(55.0, 1));

        impl().submit(SESSION_ID, req(STUDENT_ID, List.of(item(1L, "A"))));

        ArgumentCaptor<PracticeSession> captor = ArgumentCaptor.forClass(PracticeSession.class);
        verify(practiceSessionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        assertThat(captor.getValue().getSubmittedAt()).isNotNull();
    }
}
