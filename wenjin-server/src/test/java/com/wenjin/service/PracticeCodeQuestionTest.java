package com.wenjin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.GradedAnswer;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.dto.PracticeSubmitRequest;
import com.wenjin.dto.PracticeSubmitVO;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.PracticeSession;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionCodeCase;
import com.wenjin.entity.QuestionNode;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.LearningPathItemMapper;
import com.wenjin.mapper.LearningPathMapper;
import com.wenjin.mapper.PracticeSessionMapper;
import com.wenjin.mapper.QuestionCodeCaseMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.code.CodeExecutionService;
import com.wenjin.service.code.CodeExecutionService.CaseResult;
import com.wenjin.service.code.CodeExecutionService.CodeJudgeResult;
import com.wenjin.service.code.CodeExecutionService.Verdict;
import com.wenjin.service.impl.PracticeServiceImpl;
import com.wenjin.support.QuestionStatus;
import com.wenjin.support.QuestionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static com.wenjin.service.CodeTestSupport.noCodeService;
import static com.wenjin.service.CodeTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** C2：编程题在练习 start / submit 中的行为。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PracticeCodeQuestionTest {

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
    @Mock QuestionCodeCaseMapper codeCaseMapper;
    @Mock CodeExecutionService codeService;

    private static final Long STUDENT_ID = 2L;
    private static final Long COURSE_ID = 1L;
    private static final Long NODE_ID = 10L;
    private static final Long SESSION_ID = 50L;
    private static final Long CODE_Q = 7L;

    @BeforeEach
    void stubs() {
        when(practiceSessionMapper.update(any(), any())).thenReturn(1);
        when(practiceSessionMapper.selectById(SESSION_ID)).thenReturn(session());
        when(questionMapper.selectList(any())).thenReturn(List.of(codeQuestion()));
        when(questionOptionMapper.selectList(any())).thenReturn(List.of());
    }

    private PracticeServiceImpl impl(CodeExecutionService svc) {
        PracticeServiceImpl impl = new PracticeServiceImpl(
                questionNodeMapper, questionMapper, answerRecordMapper,
                kgNodeMapper, questionOptionMapper, practiceSessionMapper,
                masteryService, studentMasteryMapper, learningPathItemMapper,
                pathService, learningPathMapper, codeCaseMapper,
                svc == null ? noCodeService() : providerOf(svc));
        ReflectionTestUtils.setField(impl, "defaultSize", 5);
        ReflectionTestUtils.setField(impl, "maxSize", 10);
        ReflectionTestUtils.setField(impl, "recencyDays", 7);
        ReflectionTestUtils.setField(impl, "distractorThreshold", 2);
        ReflectionTestUtils.setField(impl, "masteredThreshold", 75.0);
        ReflectionTestUtils.setField(impl, "weakThreshold", 40.0);
        ReflectionTestUtils.setField(impl, "codeSourceMaxChars", 20);
        return impl;
    }

    private PracticeSession session() {
        PracticeSession ps = new PracticeSession();
        ps.setId(SESSION_ID);
        ps.setStudentId(STUDENT_ID);
        ps.setCourseId(COURSE_ID);
        ps.setNodeId(NODE_ID);
        ps.setQuestionIds(String.valueOf(CODE_Q));
        ps.setStatus(0);
        ps.setCreatedAt(LocalDateTime.now());
        return ps;
    }

    private Question codeQuestion() {
        Question q = new Question();
        q.setId(CODE_Q);
        q.setCourseId(COURSE_ID);
        q.setStem("读入两个整数输出和");
        q.setType(QuestionType.CODE);
        q.setStatus(QuestionStatus.APPROVED);
        q.setStarterCode("public class Main { }");
        q.setCodeLanguage("java");
        q.setAnalysis("用 Scanner");
        return q;
    }

    private PracticeSubmitRequest req(String source) {
        PracticeSubmitRequest r = new PracticeSubmitRequest();
        r.setStudentId(STUDENT_ID);
        PracticeSubmitRequest.AnswerItem ai = new PracticeSubmitRequest.AnswerItem();
        ai.setQuestionId(CODE_Q);
        ai.setStudentAnswer(source);
        r.setAnswers(List.of(ai));
        return r;
    }

    private CodeJudgeResult result(Verdict v, int passed, int total, String message) {
        List<CaseResult> cases = List.of(
                new CaseResult(1, true, false, Verdict.ACCEPTED, "1 2", "3", "3", 10),
                // 沙箱实现即便误填，hidden 内容也必须被 VO 层抹掉
                new CaseResult(2, false, true, Verdict.WRONG_ANSWER, "SECRET-IN", "SECRET-EXP", "SECRET-ACT", 12));
        return new CodeJudgeResult(v, passed, total, null, cases, 22, message);
    }

    @SuppressWarnings("unchecked")
    private List<GradedAnswer> capturedGraded() {
        ArgumentCaptor<List<GradedAnswer>> cap = ArgumentCaptor.forClass(List.class);
        verify(masteryService).applyAnswers(eq(STUDENT_ID), eq(COURSE_ID), cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("ACCEPTED：is_correct=1、进 EWMA、codeResult 附带，源码落 student_answer")
    void accepted() {
        when(codeService.judge(STUDENT_ID, CODE_Q, "class Main{}"))
                .thenReturn(result(Verdict.ACCEPTED, 2, 2, null));

        PracticeSubmitVO vo = impl(codeService).submit(SESSION_ID, req("class Main{}"));

        PracticeSubmitVO.GradeItemVO g = vo.getGraded().get(0);
        assertThat(g.getCorrect()).isTrue();
        assertThat(g.isUngraded()).isFalse();
        assertThat(g.getCodeResult().getVerdict()).isEqualTo("ACCEPTED");
        ArgumentCaptor<AnswerRecord> rec = ArgumentCaptor.forClass(AnswerRecord.class);
        verify(answerRecordMapper).insert(rec.capture());
        assertThat(rec.getValue().getIsCorrect()).isEqualTo(1);
        assertThat(rec.getValue().getStudentAnswer()).isEqualTo("class Main{}");
        assertThat(rec.getValue().getScene()).isEqualTo(2);
        assertThat(capturedGraded()).containsExactly(new GradedAnswer(CODE_Q, true));
    }

    @Test
    @DisplayName("各非通过 verdict（含 REJECTED）：is_correct=0 且进 EWMA（按错）")
    void otherVerdictsAreWrongAndCounted() {
        for (Verdict v : List.of(Verdict.WRONG_ANSWER, Verdict.COMPILE_ERROR, Verdict.RUNTIME_ERROR,
                Verdict.TIMEOUT, Verdict.RESOURCE_LIMIT, Verdict.REJECTED)) {
            org.mockito.Mockito.clearInvocations(answerRecordMapper, masteryService);
            when(codeService.judge(anyLong(), anyLong(), any())).thenReturn(result(v, 0, 2, "m"));

            PracticeSubmitVO vo = impl(codeService).submit(SESSION_ID, req("class Main{}"));

            assertThat(vo.getGraded().get(0).getCorrect()).as(v.name()).isFalse();
            assertThat(vo.getGraded().get(0).isUngraded()).as(v.name()).isFalse();
            ArgumentCaptor<AnswerRecord> rec = ArgumentCaptor.forClass(AnswerRecord.class);
            verify(answerRecordMapper).insert(rec.capture());
            assertThat(rec.getValue().getIsCorrect()).as(v.name()).isEqualTo(0);
            assertThat(capturedGraded()).as(v.name()).containsExactly(new GradedAnswer(CODE_Q, false));
        }
    }

    @Test
    @DisplayName("UNAVAILABLE / RATE_LIMITED：不写 answer_record、不进 EWMA，标记 ungraded 并带提示")
    void unavailableAndRateLimitedWriteNoEvidence() {
        for (Verdict v : List.of(Verdict.UNAVAILABLE, Verdict.RATE_LIMITED)) {
            org.mockito.Mockito.clearInvocations(answerRecordMapper, masteryService);
            when(codeService.judge(anyLong(), anyLong(), any()))
                    .thenReturn(new CodeJudgeResult(v, 0, 0, null, List.of(), 0, "稍后再试"));

            PracticeSubmitVO vo = impl(codeService).submit(SESSION_ID, req("class Main{}"));

            PracticeSubmitVO.GradeItemVO g = vo.getGraded().get(0);
            assertThat(g.isUngraded()).as(v.name()).isTrue();
            assertThat(g.getCorrect()).isNull();
            // M6：会话已关闭，提示不能叫学生「稍后重试」本题，而是重新开始一次练习
            assertThat(g.getMessage()).contains("本题未计分").contains("重新开始一次练习")
                    .doesNotContain("稍后再试").doesNotContain("稍后重试");
            assertThat(g.getCodeResult().getMessage()).isEqualTo(g.getMessage());
            assertThat(g.getCodeResult().getVerdict()).isEqualTo(v.name());
            verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
            verify(masteryService).applyAnswers(eq(STUDENT_ID), eq(COURSE_ID), eq(List.of()));
        }
    }

    @Test
    @DisplayName("无 CodeExecutionService bean：视为 UNAVAILABLE，不抛异常、不写证据")
    void noBeanIsUnavailable() {
        PracticeSubmitVO vo = impl(null).submit(SESSION_ID, req("class Main{}"));

        assertThat(vo.getGraded().get(0).isUngraded()).isTrue();
        assertThat(vo.getGraded().get(0).getMessage())
                .isEqualTo("判题服务暂不可用，本题未计分，可重新开始一次练习");
        verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
    }

    @Test
    @DisplayName("I4：answers 里同一 questionId 出现两次 → 400，判分之前就拒绝（不调用沙箱、不写任何东西）")
    void duplicateQuestionIdsRejectedBeforeJudging() {
        PracticeSubmitRequest r = req("class Main{}");
        PracticeSubmitRequest.AnswerItem again = new PracticeSubmitRequest.AnswerItem();
        again.setQuestionId(CODE_Q);
        again.setStudentAnswer("class Main{ /* 2 */ }");
        r.setAnswers(List.of(r.getAnswers().get(0), again));

        assertThatThrownBy(() -> impl(codeService).submit(SESSION_ID, r))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.BAD_REQUEST.getCode()));

        verify(codeService, never()).judge(anyLong(), anyLong(), any());
        verify(practiceSessionMapper, never()).update(any(), any());
        verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
    }

    @Test
    @DisplayName("judge 抛异常：按未判分处理，整次提交不失败")
    void judgeThrowingIsUnavailable() {
        when(codeService.judge(anyLong(), anyLong(), any())).thenThrow(new IllegalStateException("boom"));

        PracticeSubmitVO vo = impl(codeService).submit(SESSION_ID, req("class Main{}"));

        assertThat(vo.getGraded().get(0).isUngraded()).isTrue();
        verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
    }

    @Test
    @DisplayName("空源码：本地按错处理（REJECTED），不调用沙箱，仍写证据")
    void blankSourceRejectedWithoutJudge() {
        PracticeSubmitVO vo = impl(codeService).submit(SESSION_ID, req("  "));

        assertThat(vo.getGraded().get(0).getCorrect()).isFalse();
        assertThat(vo.getGraded().get(0).getCodeResult().getVerdict()).isEqualTo("REJECTED");
        verify(codeService, never()).judge(anyLong(), anyLong(), any());
        verify(answerRecordMapper).insert(any(AnswerRecord.class));
    }

    @Test
    @DisplayName("student_answer 截断到 source-max-chars")
    void sourceTruncatedWhenStored() {
        String longSource = "class Main{}" + "x".repeat(100);
        when(codeService.judge(anyLong(), anyLong(), any())).thenReturn(result(Verdict.WRONG_ANSWER, 1, 2, null));

        impl(codeService).submit(SESSION_ID, req(longSource));

        ArgumentCaptor<AnswerRecord> rec = ArgumentCaptor.forClass(AnswerRecord.class);
        verify(answerRecordMapper).insert(rec.capture());
        assertThat(rec.getValue().getStudentAnswer()).hasSize(20);
    }

    @Test
    @DisplayName("codeResult 脱敏：hidden 用例的输入/期望/实际序列化后不含任何内容")
    void hiddenCaseContentNeverSerialized() throws Exception {
        when(codeService.judge(anyLong(), anyLong(), any())).thenReturn(result(Verdict.WRONG_ANSWER, 1, 2, null));

        PracticeSubmitVO vo = impl(codeService).submit(SESSION_ID, req("class Main{}"));

        String json = new ObjectMapper().writeValueAsString(vo);
        assertThat(json).doesNotContain("SECRET");
        // 非 hidden 用例的预览仍在
        assertThat(vo.getGraded().get(0).getCodeResult().getCases().get(0).getExpectedPreview()).isEqualTo("3");
    }

    @Test
    @DisplayName("幂等重放：编程题沿用落库 is_correct，不重判")
    void replayUsesStoredCorrectness() {
        PracticeSession done = session();
        done.setStatus(1);
        when(practiceSessionMapper.selectById(SESSION_ID)).thenReturn(done);
        AnswerRecord r = new AnswerRecord();
        r.setSessionId(SESSION_ID);
        r.setQuestionId(CODE_Q);
        r.setStudentAnswer("class Main{}");
        r.setIsCorrect(1);
        when(answerRecordMapper.selectList(any())).thenReturn(List.of(r));

        PracticeSubmitVO vo = impl(codeService).submit(SESSION_ID, req("class Main{}"));

        assertThat(vo.getGraded().get(0).getCorrect()).isTrue();
        verify(codeService, never()).judge(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("start：CODE 题下发 starterCode + 仅非 hidden 的 sampleCases，序列化不含 hidden 内容")
    void startExposesOnlyNonHiddenSamples() throws Exception {
        KgNode node = new KgNode();
        node.setId(NODE_ID);
        node.setCourseId(COURSE_ID);
        node.setNodeCode("KT1");
        node.setName("n");
        node.setChapter("c");
        when(kgNodeMapper.selectById(NODE_ID)).thenReturn(node);
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        QuestionNode link = new QuestionNode();
        link.setQuestionId(CODE_Q);
        link.setNodeId(NODE_ID);
        link.setWeight(1);
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(link));
        when(practiceSessionMapper.insert(any(PracticeSession.class))).thenReturn(1);
        when(codeCaseMapper.selectList(any())).thenReturn(List.of(
                codeCase(1, "1 2", "3", 0), codeCase(2, "HIDDEN-IN", "HIDDEN-OUT", 1)));
        when(codeService.available()).thenReturn(true);

        PracticeStartVO vo = impl(codeService).start(STUDENT_ID, COURSE_ID, NODE_ID, null, null);

        var q = vo.getQuestions().get(0);
        assertThat(q.getType()).isEqualTo(5);
        assertThat(q.getStarterCode()).isEqualTo("public class Main { }");
        assertThat(q.getSampleCases()).hasSize(1);
        assertThat(q.getSampleCases().get(0).getSeq()).isEqualTo(1);
        assertThat(q.getSampleCases().get(0).getExpectedStdout()).isEqualTo("3");
        String json = new ObjectMapper().writeValueAsString(vo);
        assertThat(json).doesNotContain("HIDDEN");
    }

    // ── F2：沙箱不可用时 start 不下发 CODE 题 ──

    private void stubStartWith(Question... questions) {
        KgNode node = new KgNode();
        node.setId(NODE_ID);
        node.setCourseId(COURSE_ID);
        node.setNodeCode("KT1");
        node.setName("n");
        node.setChapter("c");
        when(kgNodeMapper.selectById(NODE_ID)).thenReturn(node);
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        List<QuestionNode> links = new java.util.ArrayList<>();
        for (Question q : questions) {
            QuestionNode link = new QuestionNode();
            link.setQuestionId(q.getId());
            link.setNodeId(NODE_ID);
            link.setWeight(1);
            links.add(link);
        }
        when(questionNodeMapper.selectList(any())).thenReturn(links);
        when(questionMapper.selectList(any())).thenReturn(List.of(questions));
        when(practiceSessionMapper.insert(any(PracticeSession.class))).thenReturn(1);
        when(codeCaseMapper.selectList(any())).thenReturn(List.of());
    }

    private Question singleQuestion() {
        Question q = new Question();
        q.setId(8L);
        q.setCourseId(COURSE_ID);
        q.setStem("选择题");
        q.setType(QuestionType.SINGLE);
        q.setStatus(QuestionStatus.APPROVED);
        q.setDifficulty(3);
        return q;
    }

    @Test
    @DisplayName("F2：无 CodeExecutionService bean → start 只出非 CODE 题")
    void startSkipsCodeWhenNoService() {
        stubStartWith(codeQuestion(), singleQuestion());

        PracticeStartVO vo = impl(null).start(STUDENT_ID, COURSE_ID, NODE_ID, null, null);

        assertThat(vo.getQuestions()).extracting(q -> q.getType()).containsOnly(QuestionType.SINGLE);
    }

    @Test
    @DisplayName("F2：available()=false → start 只出非 CODE 题；available() 抛异常同样当不可用")
    void startSkipsCodeWhenUnavailable() {
        stubStartWith(codeQuestion(), singleQuestion());
        when(codeService.available()).thenReturn(false);
        assertThat(impl(codeService).start(STUDENT_ID, COURSE_ID, NODE_ID, null, null).getQuestions())
                .extracting(q -> q.getType()).containsOnly(QuestionType.SINGLE);

        when(codeService.available()).thenThrow(new IllegalStateException("boom"));
        assertThat(impl(codeService).start(STUDENT_ID, COURSE_ID, NODE_ID, null, null).getQuestions())
                .extracting(q -> q.getType()).containsOnly(QuestionType.SINGLE);
    }

    @Test
    @DisplayName("F2：只有 CODE 题且沙箱不可用 → 题库不足")
    void startOnlyCodeAndUnavailableIsInsufficient() {
        stubStartWith(codeQuestion());
        when(codeService.available()).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> impl(codeService).start(STUDENT_ID, COURSE_ID, NODE_ID, null, null))
                .isInstanceOf(com.wenjin.common.BusinessException.class);
    }

    @Test
    @DisplayName("F2：available()=true → CODE 题与普通题都可出")
    void startKeepsCodeWhenAvailable() {
        stubStartWith(codeQuestion(), singleQuestion());
        when(codeService.available()).thenReturn(true);

        PracticeStartVO vo = impl(codeService).start(STUDENT_ID, COURSE_ID, NODE_ID, null, null);

        assertThat(vo.getQuestions()).extracting(q -> q.getType())
                .containsExactlyInAnyOrder(QuestionType.SINGLE, QuestionType.CODE);
    }

    // ── F3：判分在事务外 ──

    /** 最小事务管理器：只维护 TransactionSynchronizationManager 的“事务活动”标志。 */
    static final class FakeTxManager extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        int begun;
        int committed;
        int rolledBack;

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition d) { begun++; }
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus st) { committed++; }
        @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus st) { rolledBack++; }
    }

    @Test
    @DisplayName("F3：judge 在事务外执行，写答题记录/掌握度在事务内")
    void judgeRunsOutsideTransaction() {
        FakeTxManager tm = new FakeTxManager();
        java.util.concurrent.atomic.AtomicReference<Boolean> txDuringJudge = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Boolean> txDuringInsert = new java.util.concurrent.atomic.AtomicReference<>();
        when(codeService.judge(eq(STUDENT_ID), eq(CODE_Q), any())).thenAnswer(inv -> {
            txDuringJudge.set(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            return result(Verdict.ACCEPTED, 2, 2, null);
        });
        when(answerRecordMapper.insert(any(AnswerRecord.class))).thenAnswer(inv -> {
            txDuringInsert.set(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            return 1;
        });
        PracticeServiceImpl svc = impl(codeService);
        svc.setTransactionManager(tm);

        PracticeSubmitVO vo = svc.submit(SESSION_ID, req("class Main{}"));

        assertThat(vo.getGraded().get(0).getCorrect()).isTrue();
        assertThat(txDuringJudge.get()).isFalse();
        assertThat(txDuringInsert.get()).isTrue();
        assertThat(tm.begun).isEqualTo(1);
        assertThat(tm.committed).isEqualTo(1);
    }

    @Test
    @DisplayName("F3：CAS 输掉 → 丢弃预判结果走重放，不写记录、不调 applyAnswers")
    void casLostDiscardsPrecomputedJudge() {
        FakeTxManager tm = new FakeTxManager();
        when(codeService.judge(eq(STUDENT_ID), eq(CODE_Q), any())).thenReturn(result(Verdict.ACCEPTED, 2, 2, null));
        when(practiceSessionMapper.update(any(), any())).thenReturn(0);
        AnswerRecord stored = new AnswerRecord();
        stored.setSessionId(SESSION_ID);
        stored.setQuestionId(CODE_Q);
        stored.setStudentAnswer("class Main{}");
        stored.setIsCorrect(0);
        when(answerRecordMapper.selectList(any())).thenReturn(List.of(stored));
        PracticeServiceImpl svc = impl(codeService);
        svc.setTransactionManager(tm);

        PracticeSubmitVO vo = svc.submit(SESSION_ID, req("class Main{}"));

        // 结果来自先提交者落库的记录（错），而非本线程的预判（对）
        assertThat(vo.getGraded().get(0).getCorrect()).isFalse();
        verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
        verify(masteryService, never()).applyAnswers(anyLong(), anyLong(), anyList());
    }

    @Test
    @DisplayName("F3：事务段抛异常 → 回滚")
    void failureInTransactionRollsBack() {
        FakeTxManager tm = new FakeTxManager();
        when(codeService.judge(eq(STUDENT_ID), eq(CODE_Q), any())).thenReturn(result(Verdict.ACCEPTED, 2, 2, null));
        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(masteryService).applyAnswers(anyLong(), anyLong(), anyList());
        PracticeServiceImpl svc = impl(codeService);
        svc.setTransactionManager(tm);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.submit(SESSION_ID, req("class Main{}")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tm.rolledBack).isEqualTo(1);
    }

    private QuestionCodeCase codeCase(int seq, String in, String out, int hidden) {
        QuestionCodeCase c = new QuestionCodeCase();
        c.setQuestionId(CODE_Q);
        c.setSeq(seq);
        c.setStdin(in);
        c.setExpectedStdout(out);
        c.setHidden(hidden);
        return c;
    }
}
