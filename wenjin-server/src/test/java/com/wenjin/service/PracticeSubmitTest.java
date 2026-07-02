package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.dto.GradedAnswer;
import com.wenjin.dto.PathGenerateRequest;
import com.wenjin.dto.PracticeSubmitRequest;
import com.wenjin.dto.PracticeSubmitVO;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.LearningPath;
import com.wenjin.entity.LearningPathItem;
import com.wenjin.entity.PracticeSession;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionOption;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.LearningPathItemMapper;
import com.wenjin.mapper.LearningPathMapper;
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
 *   <li>distractor 归因：命中 ≥2 次的前置出现，1 次不出现（T5）</li>
 *   <li>自动通过判定：mastery ≥ 75 时路径 item 置完成（T5）</li>
 *   <li>自动通过幂等：item 已完成不重复写（T5）</li>
 *   <li>自由练习（pathItemId=null）不碰路径 item（T5）</li>
 *   <li>幂等重放（status=1）：weakPreqs 纯读重算、itemCompleted 只读状态（T5）</li>
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
    @Mock LearningPathItemMapper learningPathItemMapper;
    @Mock PathService pathService;
    @Mock LearningPathMapper learningPathMapper;

    private static final Long STUDENT_ID  = 2L;
    private static final Long COURSE_ID   = 1L;
    private static final Long NODE_ID     = 10L;
    private static final Long SESSION_ID  = 50L;
    private static final Long PATH_ITEM_ID = 88L;

    // ── 构造 impl（注入所有依赖） ────────────────────────────────────────────

    private PracticeServiceImpl impl() {
        PracticeServiceImpl impl = new PracticeServiceImpl(
                questionNodeMapper, questionMapper, answerRecordMapper,
                kgNodeMapper, questionOptionMapper, practiceSessionMapper,
                masteryService, studentMasteryMapper, learningPathItemMapper,
                pathService, learningPathMapper);
        ReflectionTestUtils.setField(impl, "defaultSize", 5);
        ReflectionTestUtils.setField(impl, "maxSize", 10);
        ReflectionTestUtils.setField(impl, "recencyDays", 7);
        ReflectionTestUtils.setField(impl, "distractorThreshold", 2);
        ReflectionTestUtils.setField(impl, "masteredThreshold", 75.0);
        ReflectionTestUtils.setField(impl, "weakThreshold", 40.0);
        return impl;
    }

    // ── fixture 工具方法 ──────────────────────────────────────────────────────

    private PracticeSession session(int status, String questionIds) {
        return session(status, questionIds, null);
    }

    private PracticeSession session(int status, String questionIds, Long pathItemId) {
        PracticeSession ps = new PracticeSession();
        ps.setId(SESSION_ID);
        ps.setStudentId(STUDENT_ID);
        ps.setCourseId(COURSE_ID);
        ps.setNodeId(NODE_ID);
        ps.setQuestionIds(questionIds);
        ps.setStatus(status);
        ps.setPathItemId(pathItemId);
        ps.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        return ps;
    }

    private Question question(Long id, int type) {
        return question(id, type, null);
    }

    private Question question(Long id, int type, String analysis) {
        Question q = new Question();
        q.setId(id);
        q.setCourseId(COURSE_ID);
        q.setStem("题干" + id);
        q.setType(type);
        q.setDifficulty(3);
        q.setAnalysis(analysis);
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

    /**
     * 错误选项，绑定 distractor → nodeCode 映射。
     */
    private QuestionOption wrongOpt(Long questionId, String key, String pointNodeCode) {
        QuestionOption o = new QuestionOption();
        o.setQuestionId(questionId);
        o.setOptionKey(key);
        o.setOptionText("选项" + key);
        o.setIsCorrect(0);
        o.setPointNodeCode(pointNodeCode);
        return o;
    }

    private KgNode kgNode(String nodeCode, String name) {
        KgNode n = new KgNode();
        n.setId(99L);
        n.setNodeCode(nodeCode);
        n.setName(name);
        n.setCourseId(COURSE_ID);
        return n;
    }

    private LearningPathItem pathItem(Long id, int status) {
        LearningPathItem item = new LearningPathItem();
        item.setId(id);
        item.setStatus(status);
        item.setNodeId(NODE_ID);
        item.setPathId(200L);
        item.setStepOrder(1);
        return item;
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
        // 两道单选题：q1 答对（题库带解析），q2 答错（题库无解析）
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1,2"));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(
                        question(1L, QuestionType.SINGLE, "q1 的解析文本"),
                        question(2L, QuestionType.SINGLE)));
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
        // q1 答对，analysis = 题库解析文本
        PracticeSubmitVO.GradeItemVO g1 = vo.getGraded().get(0);
        assertThat(g1.getQuestionId()).isEqualTo(1L);
        assertThat(g1.getCorrect()).isTrue();
        assertThat(g1.getCorrectAnswer()).isEqualTo("A");
        assertThat(g1.getAnalysis()).isEqualTo("q1 的解析文本");
        // q2 答错，题库无解析 → analysis=null
        PracticeSubmitVO.GradeItemVO g2 = vo.getGraded().get(1);
        assertThat(g2.getQuestionId()).isEqualTo(2L);
        assertThat(g2.getCorrect()).isFalse();
        assertThat(g2.getCorrectAnswer()).isEqualTo("B");
        assertThat(g2.getAnalysis()).isNull();
        // 掌握度字段
        assertThat(vo.getMasteryBefore()).isEqualTo(50.0);
        assertThat(vo.getMasteryAfter()).isEqualTo(55.0);
        assertThat(vo.getMasteryLevel()).isEqualTo("薄弱");
        // T5/T6 默认值（自由练习无 pathItemId）
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
                .thenReturn(List.of(
                        question(1L, QuestionType.SINGLE, "q1 的解析文本"),
                        question(2L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A"), correctOpt(2L, "B")));
        when(studentMasteryMapper.selectOne(any())).thenReturn(mastery(55.0, 1));

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "A"), item(2L, "C"))));

        // 关键：不写库、不调 applyAnswers
        verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
        verify(masteryService, never()).applyAnswers(any(), any(), any());

        // 重建了 graded（2 道题），重放路径同样带 analysis
        assertThat(vo.getGraded()).hasSize(2);
        assertThat(vo.getGraded().get(0).getAnalysis()).isEqualTo("q1 的解析文本");
        assertThat(vo.getGraded().get(1).getAnalysis()).isNull();
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

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 T5-A：distractor 归因——命中 ≥2 次出现，1 次不出现
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T5 distractor：同一前置被 2 题各错选一次(共 2 次)→ 出现；另一前置仅 1 次 → 不出现")
    void submit_distractorAggregation_thresholdFilterWorks() {
        // q1 答 B(错)，B→KT99；q2 答 B(错)，B→KT99；q3 答 B(错)，B→KT77（只命中 1 次）
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1,2,3"));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(
                        question(1L, QuestionType.SINGLE),
                        question(2L, QuestionType.SINGLE),
                        question(3L, QuestionType.SINGLE)));
        // 正确选项 A；错误选项 B 带 pointNodeCode
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(
                        correctOpt(1L, "A"), wrongOpt(1L, "B", "KT99"),
                        correctOpt(2L, "A"), wrongOpt(2L, "B", "KT99"),
                        correctOpt(3L, "A"), wrongOpt(3L, "B", "KT77")));
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(50.0, 1))
                .thenReturn(mastery(55.0, 1));
        // KT99 节点名查询
        when(kgNodeMapper.selectList(any()))
                .thenReturn(List.of(kgNode("KT99", "先修知识99")));

        // 全部答 B（答错）
        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "B"), item(2L, "B"), item(3L, "B"))));

        assertThat(vo.getWeakPrerequisites()).hasSize(1);
        PracticeSubmitVO.WeakPrerequisiteVO wp = vo.getWeakPrerequisites().get(0);
        assertThat(wp.getNodeCode()).isEqualTo("KT99");
        assertThat(wp.getName()).isEqualTo("先修知识99");
        assertThat(wp.getHitCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("T5 distractor：所有题答对 → weakPrerequisites 为空")
    void submit_distractorAggregation_noWrongAnswers_emptyWeakPreqs() {
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1,2"));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE), question(2L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A"), correctOpt(2L, "B")));
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(80.0, 2))
                .thenReturn(mastery(82.0, 2));

        // 全部答对
        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "A"), item(2L, "B"))));

        assertThat(vo.getWeakPrerequisites()).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 T5-B：路径 item 自动通过（mastery ≥ 75，pathItemId 非 null）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T5 自动通过：掌握度 ≥ 75 且 session.pathItemId 非 null → itemCompleted=true，item 置完成")
    void submit_autoCompletePathItem_whenMasteredAndPathItemSet() {
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1", PATH_ITEM_ID));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A")));
        // 提交前 50，提交后 80（≥ 75）
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(50.0, 1))
                .thenReturn(mastery(80.0, 2));
        // 路径 item：当前 PENDING(0)
        when(learningPathItemMapper.selectById(PATH_ITEM_ID))
                .thenReturn(pathItem(PATH_ITEM_ID, 0));

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "A"))));

        assertThat(vo.isItemCompleted()).isTrue();
        // item 被更新为 DONE
        ArgumentCaptor<LearningPathItem> captor = ArgumentCaptor.forClass(LearningPathItem.class);
        verify(learningPathItemMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(1); // ITEM_DONE
        assertThat(captor.getValue().getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("T5 自动通过：掌握度 < 75 时 itemCompleted=false，不更新 item")
    void submit_autoCompletePathItem_notMastered_itemNotCompleted() {
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1", PATH_ITEM_ID));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A")));
        // 提交前 50，提交后 60（< 75）
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(50.0, 1))
                .thenReturn(mastery(60.0, 1));
        when(learningPathItemMapper.selectById(PATH_ITEM_ID))
                .thenReturn(pathItem(PATH_ITEM_ID, 0));

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "A"))));

        assertThat(vo.isItemCompleted()).isFalse();
        verify(learningPathItemMapper, never()).updateById(any(LearningPathItem.class));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 T5-C：item 已完成时自动通过幂等（不重复写）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T5 幂等：item 已 DONE → itemCompleted=true，不重复调 updateById")
    void submit_autoCompletePathItem_alreadyDone_idempotentNoRewrite() {
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1", PATH_ITEM_ID));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A")));
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(50.0, 1))
                .thenReturn(mastery(80.0, 2));
        // item 已经是 DONE(1)
        when(learningPathItemMapper.selectById(PATH_ITEM_ID))
                .thenReturn(pathItem(PATH_ITEM_ID, 1));

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "A"))));

        assertThat(vo.isItemCompleted()).isTrue();
        // 幂等：item 已完成，不再调 updateById
        verify(learningPathItemMapper, never()).updateById(any(LearningPathItem.class));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 T5-D：自由练习（pathItemId=null）不碰路径 item
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T5 自由练习：pathItemId=null → itemCompleted=false，不调 learningPathItemMapper")
    void submit_freePractice_noPathItemIdSet_itemCompletedFalse() {
        // session.pathItemId = null（自由练习）
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1", null));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A")));
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(50.0, 1))
                .thenReturn(mastery(90.0, 2)); // 即使超 75 也不应完成 item

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "A"))));

        assertThat(vo.isItemCompleted()).isFalse();
        // 不碰 learningPathItemMapper
        verify(learningPathItemMapper, never()).selectById(any());
        verify(learningPathItemMapper, never()).updateById(any(LearningPathItem.class));
    }

    // ── T6 fixture helpers ────────────────────────────────────────────────────

    /**
     * 模拟 StudentMastery 记录（nodeId 参数化，供条件 (a) 中前置节点掌握度查询使用）。
     */
    private StudentMastery masteryForNode(Long nodeId, double score, int level) {
        StudentMastery sm = new StudentMastery();
        sm.setStudentId(STUDENT_ID);
        sm.setCourseId(COURSE_ID);
        sm.setNodeId(nodeId);
        sm.setMasteryScore(BigDecimal.valueOf(score));
        sm.setMasteryLevel(level);
        return sm;
    }

    /** 有效学习路径（供条件 (b) "是否在当前路径中"查询使用）。 */
    private LearningPath activeLearningPath(Long pathId) {
        LearningPath lp = new LearningPath();
        lp.setId(pathId);
        lp.setStudentId(STUDENT_ID);
        lp.setCourseId(COURSE_ID);
        lp.setStatus(1); // active
        lp.setGeneratedAt(LocalDateTime.now().minusHours(1));
        return lp;
    }

    /** 路径步骤（nodeId 参数化，供 nodeInActivePath 检查使用）。 */
    private LearningPathItem pathItemWithNode(Long pathId, Long nodeId) {
        LearningPathItem item = new LearningPathItem();
        item.setId(300L);
        item.setPathId(pathId);
        item.setNodeId(nodeId);
        item.setStepOrder(1);
        item.setStatus(0); // PENDING
        return item;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 T5-E：幂等重放（status=1）weakPreqs 纯读重算、itemCompleted 只读状态
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T5 幂等重放：weakPreqs 从存档重算，itemCompleted 只读 item.status，不写库")
    void submit_idempotentReplay_computesWeakPreqsAndReadsItemCompleted() {
        // session 已提交（status=1），有 pathItemId
        PracticeSession alreadySubmitted = session(1, "1,2", PATH_ITEM_ID);
        alreadySubmitted.setSubmittedAt(LocalDateTime.now().minusMinutes(1));
        when(practiceSessionMapper.selectById(SESSION_ID)).thenReturn(alreadySubmitted);

        // 存档 answer_record：q1 答 B(错)，q2 答 B(错)，两题 B→KT99
        AnswerRecord ar1 = new AnswerRecord();
        ar1.setQuestionId(1L);
        ar1.setStudentAnswer("B");
        ar1.setIsCorrect(0);
        ar1.setScene(2);
        ar1.setSessionId(SESSION_ID);

        AnswerRecord ar2 = new AnswerRecord();
        ar2.setQuestionId(2L);
        ar2.setStudentAnswer("B");
        ar2.setIsCorrect(0);
        ar2.setScene(2);
        ar2.setSessionId(SESSION_ID);

        when(answerRecordMapper.selectList(any())).thenReturn(List.of(ar1, ar2));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE), question(2L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(
                        correctOpt(1L, "A"), wrongOpt(1L, "B", "KT99"),
                        correctOpt(2L, "A"), wrongOpt(2L, "B", "KT99")));
        when(studentMasteryMapper.selectOne(any())).thenReturn(mastery(80.0, 2));
        // 节点名
        when(kgNodeMapper.selectList(any()))
                .thenReturn(List.of(kgNode("KT99", "先修知识99")));
        // item 已完成
        when(learningPathItemMapper.selectById(PATH_ITEM_ID))
                .thenReturn(pathItem(PATH_ITEM_ID, 1));

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "B"), item(2L, "B"))));

        // 不写库、不调 applyAnswers
        verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
        verify(masteryService, never()).applyAnswers(any(), any(), any());
        // 不写 item（只读）
        verify(learningPathItemMapper, never()).updateById(any(LearningPathItem.class));

        // weakPreqs 从存档重算：KT99 命中 2 次
        assertThat(vo.getWeakPrerequisites()).hasSize(1);
        assertThat(vo.getWeakPrerequisites().get(0).getNodeCode()).isEqualTo("KT99");
        assertThat(vo.getWeakPrerequisites().get(0).getHitCount()).isEqualTo(2);

        // itemCompleted 只读：item.status=1(DONE) → true
        assertThat(vo.isItemCompleted()).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 T6-A：路径重算条件 (a)——薄弱前置节点掌握等级 < 2 时触发
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T6 condA 触发：weakPreqs 存在命中≥2 且掌握等级<2 的前置→ pathRegenerated=true，PathService.generate 被调用")
    void pathRegen_condA_triggers_whenWeakPreqLevelBelow2() {
        // 2 道题，全部答错 B，B→KT99（命中 2 次 ≥ distractorThreshold）
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1,2"));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE), question(2L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(
                        correctOpt(1L, "A"), wrongOpt(1L, "B", "KT99"),
                        correctOpt(2L, "A"), wrongOpt(2L, "B", "KT99")));
        // after mastery = 55.0（≥ 40，condB = false；只验 condA）
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(50.0, 1))
                .thenReturn(mastery(55.0, 1));
        // aggregateWeakPrerequisites 中第一次 kgNodeMapper.selectList → KT99 节点
        // anyWeakPreqBelowLevel2 中第二次 kgNodeMapper.selectList → 同节点，id=99L
        when(kgNodeMapper.selectList(any()))
                .thenReturn(List.of(kgNode("KT99", "先修知识99")));
        // KT99（nodeId=99L）当前掌握度为 0（未学），masteryLevel=0 < 2
        when(studentMasteryMapper.selectList(any()))
                .thenReturn(List.of(masteryForNode(99L, 0.0, 0)));

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "B"), item(2L, "B"))));

        assertThat(vo.isPathRegenerated()).isTrue();
        // PathService.generate 被调用，参数：studentId=STUDENT_ID，courseId=COURSE_ID，targetNodeId=null
        ArgumentCaptor<PathGenerateRequest> captor = ArgumentCaptor.forClass(PathGenerateRequest.class);
        verify(pathService).generate(captor.capture());
        PathGenerateRequest genReq = captor.getValue();
        assertThat(genReq.getStudentId()).isEqualTo(STUDENT_ID);
        assertThat(genReq.getCourseId()).isEqualTo(COURSE_ID);
        assertThat(genReq.getTargetNodeId()).isNull();
        assertThat(genReq.isUseAi()).isFalse();
    }

    @Test
    @DisplayName("T6 condA 不触发：weakPreqs 存在但前置节点掌握等级=2（已掌握）→ pathRegenerated=false")
    void pathRegen_condA_noTrigger_whenWeakPreqAllMastered() {
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1,2"));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE), question(2L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(
                        correctOpt(1L, "A"), wrongOpt(1L, "B", "KT99"),
                        correctOpt(2L, "A"), wrongOpt(2L, "B", "KT99")));
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(50.0, 1))
                .thenReturn(mastery(55.0, 1));
        when(kgNodeMapper.selectList(any()))
                .thenReturn(List.of(kgNode("KT99", "先修知识99")));
        // KT99（nodeId=99L）已掌握，masteryLevel=2
        when(studentMasteryMapper.selectList(any()))
                .thenReturn(List.of(masteryForNode(99L, 80.0, 2)));

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "B"), item(2L, "B"))));

        assertThat(vo.isPathRegenerated()).isFalse();
        verify(pathService, never()).generate(any(PathGenerateRequest.class));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 T6-B：路径重算条件 (b)——练后掌握度 < weakThreshold 且目标节点不在当前路径中
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T6 condB 触发：练后掌握度<40 且目标节点不在当前路径中 → pathRegenerated=true")
    void pathRegen_condB_triggers_whenLowMasteryNodeNotInPath() {
        // 全部答对（weakPreqs 为空，condA=false），after mastery=25（<40）
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1"));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A")));
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(20.0, 0))   // before
                .thenReturn(mastery(25.0, 0));  // after = 25.0 < 40
        // 无当前有效路径 → nodeInActivePath = false
        when(learningPathMapper.selectList(any())).thenReturn(List.of());

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "A"))));

        assertThat(vo.isPathRegenerated()).isTrue();
        ArgumentCaptor<PathGenerateRequest> captor = ArgumentCaptor.forClass(PathGenerateRequest.class);
        verify(pathService).generate(captor.capture());
        assertThat(captor.getValue().getStudentId()).isEqualTo(STUDENT_ID);
        assertThat(captor.getValue().getCourseId()).isEqualTo(COURSE_ID);
        assertThat(captor.getValue().getTargetNodeId()).isNull();
    }

    @Test
    @DisplayName("T6 condB 不触发：练后掌握度<40 但目标节点已在当前路径中 → pathRegenerated=false")
    void pathRegen_condB_noTrigger_whenLowMasteryNodeInActivePath() {
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1"));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A")));
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(20.0, 0))
                .thenReturn(mastery(25.0, 0));  // 25.0 < 40，但节点在路径中
        // 有效路径存在
        Long activePathId = 200L;
        when(learningPathMapper.selectList(any()))
                .thenReturn(List.of(activeLearningPath(activePathId)));
        // 路径包含目标节点 NODE_ID=10L
        when(learningPathItemMapper.selectList(any()))
                .thenReturn(List.of(pathItemWithNode(activePathId, NODE_ID)));

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "A"))));

        assertThat(vo.isPathRegenerated()).isFalse();
        verify(pathService, never()).generate(any(PathGenerateRequest.class));
    }

    @Test
    @DisplayName("T6 condB 不触发：练后掌握度≥40 → pathRegenerated=false（不查路径）")
    void pathRegen_condB_noTrigger_whenMasteryAboveWeakThreshold() {
        when(practiceSessionMapper.selectById(SESSION_ID))
                .thenReturn(session(0, "1"));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(correctOpt(1L, "A")));
        // after mastery = 50.0（≥ 40），condB short-circuits
        when(studentMasteryMapper.selectOne(any()))
                .thenReturn(mastery(45.0, 1))
                .thenReturn(mastery(50.0, 1));

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "A"))));

        assertThat(vo.isPathRegenerated()).isFalse();
        verify(pathService, never()).generate(any(PathGenerateRequest.class));
        // 不应查询路径（条件 (b) 因分数短路而跳过）
        verify(learningPathMapper, never()).selectList(any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 用例 T6-C：幂等重放路径——pathRegenerated 恒 false
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T6 幂等重放：session.status=1 → pathRegenerated 恒 false，PathService.generate 不被调用")
    void pathRegen_idempotentReplay_alwaysFalse() {
        PracticeSession alreadySubmitted = session(1, "1,2");
        alreadySubmitted.setSubmittedAt(LocalDateTime.now().minusMinutes(1));
        when(practiceSessionMapper.selectById(SESSION_ID)).thenReturn(alreadySubmitted);

        // 存档 answer_record（有 KT99 distractor，理论上 condA 可触发）
        AnswerRecord ar1 = new AnswerRecord();
        ar1.setQuestionId(1L);
        ar1.setStudentAnswer("B");
        ar1.setIsCorrect(0);
        ar1.setScene(2);
        ar1.setSessionId(SESSION_ID);
        AnswerRecord ar2 = new AnswerRecord();
        ar2.setQuestionId(2L);
        ar2.setStudentAnswer("B");
        ar2.setIsCorrect(0);
        ar2.setScene(2);
        ar2.setSessionId(SESSION_ID);
        when(answerRecordMapper.selectList(any())).thenReturn(List.of(ar1, ar2));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, QuestionType.SINGLE), question(2L, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(
                        correctOpt(1L, "A"), wrongOpt(1L, "B", "KT99"),
                        correctOpt(2L, "A"), wrongOpt(2L, "B", "KT99")));
        when(kgNodeMapper.selectList(any()))
                .thenReturn(List.of(kgNode("KT99", "先修知识99")));
        when(studentMasteryMapper.selectOne(any())).thenReturn(mastery(30.0, 0));

        PracticeSubmitVO vo = impl().submit(SESSION_ID,
                req(STUDENT_ID, List.of(item(1L, "B"), item(2L, "B"))));

        // 重放路径：pathRegenerated 恒 false，generate 不被调用
        assertThat(vo.isPathRegenerated()).isFalse();
        verify(pathService, never()).generate(any(PathGenerateRequest.class));
        // 且不写任何库表
        verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
        verify(masteryService, never()).applyAnswers(any(), any(), any());
    }
}
