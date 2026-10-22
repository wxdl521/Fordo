package com.wenjin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.GradedAnswer;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.dto.PaperVO;
import com.wenjin.dto.QuestionGradeVO;
import com.wenjin.dto.SubmitRequest;
import com.wenjin.dto.SubmitResult;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.DiagnosticSession;
import com.wenjin.entity.DiagnosticSessionQuestion;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.DiagnosticSessionMapper;
import com.wenjin.mapper.DiagnosticSessionQuestionMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.impl.DiagnosticServiceImpl;
import com.wenjin.support.QuestionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DiagnosticServiceImpl 单元测试：组卷分层 + 会话冻结 + 攻击面（精确集合 / 归属 / 幂等）。
 */
@ExtendWith(MockitoExtension.class)
class DiagnosticServiceImplTest {

    @Mock QuestionMapper questionMapper;
    @Mock QuestionOptionMapper questionOptionMapper;
    @Mock QuestionNodeMapper questionNodeMapper;
    @Mock KgNodeMapper kgNodeMapper;
    @Mock AnswerRecordMapper answerRecordMapper;
    @Mock DiagnosticSessionMapper diagnosticSessionMapper;
    @Mock DiagnosticSessionQuestionMapper diagnosticSessionQuestionMapper;
    @Mock MasteryService masteryService;
    @Mock CourseService courseService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FeatureFlagsProperties featureFlags;

    private static final Long COURSE_ID = 1L;
    private static final Long STUDENT_ID = 10L;
    private static final String TOKEN = "11111111-2222-3333-4444-555555555555";

    private static final int CHAPTERS = 5;
    private static final int PER_CHAPTER = 6;

    private List<Question> allQuestions;
    private List<QuestionNode> allQuestionNodes;
    private List<KgNode> allKgNodes;
    private final AtomicLong sessionIdSeq = new AtomicLong(1000);

    @BeforeEach
    void buildFixture() {
        featureFlags = new FeatureFlagsProperties();
        featureFlags.setFixedDiagnosticEnabled(true);
        CurrentUser.set(new CurrentPrincipal(STUDENT_ID, UserRole.STUDENT));

        allQuestions = new ArrayList<>();
        allQuestionNodes = new ArrayList<>();
        allKgNodes = new ArrayList<>();

        String[] chapters = {"第一章", "第二章", "第三章", "第四章", "第五章"};
        for (int c = 0; c < CHAPTERS; c++) {
            long nodeId = (c + 1) * 100L;
            KgNode node = new KgNode();
            node.setId(nodeId);
            node.setCourseId(COURSE_ID);
            node.setNodeCode("KT0" + (c + 1));
            node.setName("知识点" + (c + 1));
            node.setChapter(chapters[c]);
            node.setDifficulty(3);
            node.setIsKey(c % 2 == 0 ? 1 : 0);
            allKgNodes.add(node);

            for (int q = 0; q < PER_CHAPTER; q++) {
                long questionId = (long) (c * PER_CHAPTER + q + 1);
                Question question = new Question();
                question.setId(questionId);
                question.setCourseId(COURSE_ID);
                question.setStem("题干-章" + (c + 1) + "-题" + (q + 1));
                question.setType(1);
                question.setDifficulty(3);
                question.setAnswer("A");
                question.setStatus(QuestionStatus.APPROVED);
                allQuestions.add(question);

                QuestionNode qn = new QuestionNode();
                qn.setId(questionId);
                qn.setQuestionId(questionId);
                qn.setNodeId(nodeId);
                qn.setWeight(1);
                allQuestionNodes.add(qn);
            }
        }
    }

    @AfterEach
    void clearUser() {
        CurrentUser.clear();
    }

    private DiagnosticServiceImpl impl() {
        DiagnosticServiceImpl svc = new DiagnosticServiceImpl(
                questionMapper, questionOptionMapper, questionNodeMapper, kgNodeMapper,
                answerRecordMapper, diagnosticSessionMapper, diagnosticSessionQuestionMapper,
                masteryService, courseService, featureFlags, objectMapper);
        ReflectionTestUtils.setField(svc, "paperSize", 25);
        return svc;
    }

    private void stubComposeHappyPath(List<Question> questions,
                                      List<QuestionNode> qns,
                                      List<KgNode> nodes) {
        when(questionMapper.selectList(any())).thenReturn(questions);
        when(questionNodeMapper.selectList(any())).thenReturn(qns);
        when(kgNodeMapper.selectList(any())).thenReturn(nodes);
        when(questionOptionMapper.selectList(any())).thenAnswer(inv -> optionsForAll(questions));
        doAnswer(inv -> {
            DiagnosticSession s = inv.getArgument(0);
            s.setId(sessionIdSeq.incrementAndGet());
            return 1;
        }).when(diagnosticSessionMapper).insert(any(DiagnosticSession.class));
        when(diagnosticSessionQuestionMapper.insert(any(DiagnosticSessionQuestion.class))).thenReturn(1);
    }

    private List<QuestionOption> twoOptions(Long questionId) {
        QuestionOption a = new QuestionOption();
        a.setId(questionId * 10 + 1);
        a.setQuestionId(questionId);
        a.setOptionKey("A");
        a.setOptionText("选项A文本");
        a.setIsCorrect(1);

        QuestionOption b = new QuestionOption();
        b.setId(questionId * 10 + 2);
        b.setQuestionId(questionId);
        b.setOptionKey("B");
        b.setOptionText("选项B文本");
        b.setIsCorrect(0);
        b.setPointNodeCode("KT01");
        return List.of(a, b);
    }

    private List<QuestionOption> optionsForAll(List<Question> questions) {
        List<QuestionOption> result = new ArrayList<>();
        for (Question q : questions) {
            result.addAll(twoOptions(q.getId()));
        }
        return result;
    }

    private DiagnosticSession inProgressSession(Long sessionId, Long studentId, Long courseId) {
        DiagnosticSession s = new DiagnosticSession();
        s.setId(sessionId);
        s.setToken(TOKEN);
        s.setStudentId(studentId);
        s.setCourseId(courseId);
        s.setStatus(0);
        return s;
    }

    private List<DiagnosticSessionQuestion> frozen(Long sessionId, Long... qids) {
        List<DiagnosticSessionQuestion> rows = new ArrayList<>();
        for (int i = 0; i < qids.length; i++) {
            DiagnosticSessionQuestion r = new DiagnosticSessionQuestion();
            r.setId((long) (i + 1));
            r.setSessionId(sessionId);
            r.setQuestionId(qids[i]);
            r.setCourseId(COURSE_ID);
            r.setOrdinal(i);
            rows.add(r);
        }
        return rows;
    }

    private Question approved(long id) {
        Question q = new Question();
        q.setId(id);
        q.setCourseId(COURSE_ID);
        q.setType(1);
        q.setStatus(QuestionStatus.APPROVED);
        return q;
    }

    private SubmitRequest.Answer ans(long qid, String key) {
        SubmitRequest.Answer a = new SubmitRequest.Answer();
        a.setQuestionId(qid);
        a.setOptionKey(key);
        return a;
    }

    private SubmitRequest submitReq(List<SubmitRequest.Answer> answers) {
        SubmitRequest req = new SubmitRequest();
        req.setStudentId(STUDENT_ID);
        req.setCourseId(COURSE_ID);
        req.setSessionToken(TOKEN);
        req.setAnswers(answers);
        return req;
    }

    // ───────────────────── 组卷 ─────────────────────

    @Test
    @DisplayName("A 正常组卷：30题/5章 → total=25，冻结会话+token，覆盖5章")
    void normalPaperComposed() {
        stubComposeHappyPath(allQuestions, allQuestionNodes, allKgNodes);

        PaperVO paper = impl().composePaper(COURSE_ID);

        assertThat(paper.getTotal()).isEqualTo(25);
        assertThat(paper.getSessionToken()).isNotBlank();
        assertThat(paper.getCourseId()).isEqualTo(COURSE_ID);
        Set<String> covered = paper.getQuestions().stream()
                .map(PaperQuestionVO::getChapter).collect(Collectors.toSet());
        assertThat(covered).hasSize(5);

        verify(courseService).assertAccessibleByStudent(STUDENT_ID, COURSE_ID);
        verify(diagnosticSessionMapper).insert(any(DiagnosticSession.class));
        verify(diagnosticSessionQuestionMapper, times(25)).insert(any(DiagnosticSessionQuestion.class));
    }

    @Test
    @DisplayName("C2 固定诊断卷排除编程题：即使 CODE 题已 APPROVED 且挂在章节主节点上也不入卷")
    void composePaperExcludesCodeQuestions() {
        List<Question> pool = new ArrayList<>(allQuestions);
        List<QuestionNode> qns = new ArrayList<>(allQuestionNodes);
        for (long id = 900; id < 905; id++) {
            Question code = new Question();
            code.setId(id);
            code.setCourseId(COURSE_ID);
            code.setStem("编程题" + id);
            code.setType(5);
            code.setStatus(QuestionStatus.APPROVED);
            pool.add(code);
            QuestionNode qn = new QuestionNode();
            qn.setId(id);
            qn.setQuestionId(id);
            qn.setNodeId(100L);
            qn.setWeight(1);
            qns.add(qn);
        }
        stubComposeHappyPath(pool, qns, allKgNodes);

        PaperVO paper = impl().composePaper(COURSE_ID);

        assertThat(paper.getQuestions()).isNotEmpty();
        assertThat(paper.getQuestions()).noneMatch(q -> q.getType() != null && q.getType() == 5);
        assertThat(paper.getQuestions()).noneMatch(q -> q.getQuestionId() >= 900);
    }

    @Test
    @DisplayName("B 空题库：total=0，不建会话、无 token")
    void emptyPoolReturnsEmptyPaper() {
        when(questionMapper.selectList(any())).thenReturn(List.of());

        PaperVO paper = impl().composePaper(COURSE_ID);

        assertThat(paper.getTotal()).isZero();
        assertThat(paper.getQuestions()).isEmpty();
        assertThat(paper.getSessionToken()).isNull();
        verify(diagnosticSessionMapper, never()).insert(any(DiagnosticSession.class));
    }

    @Test
    @DisplayName("C 答案不泄露：Jackson 序列化不含 answer/isCorrect")
    void noAnswerLeakage() throws Exception {
        stubComposeHappyPath(allQuestions, allQuestionNodes, allKgNodes);

        PaperVO paper = impl().composePaper(COURSE_ID);
        String json = objectMapper.writeValueAsString(paper.getQuestions().get(0));
        assertThat(json).doesNotContain("answer");
        assertThat(json).doesNotContain("isCorrect");
        assertThat(json).doesNotContain("pointNodeCode");
    }

    @Test
    @DisplayName("归档主节点：仅归档主节点的题排除，仍有 active 主节点的题保留")
    void compose_excludesQuestionsWithoutAnyActiveMainNode() {
        Question archivedOnly = approved(901L);
        Question mixed = approved(902L);

        QuestionNode archivedOnlyLink = new QuestionNode();
        archivedOnlyLink.setQuestionId(archivedOnly.getId());
        archivedOnlyLink.setNodeId(900L);
        archivedOnlyLink.setWeight(1);

        QuestionNode mixedArchivedLink = new QuestionNode();
        mixedArchivedLink.setQuestionId(mixed.getId());
        mixedArchivedLink.setNodeId(900L);
        mixedArchivedLink.setWeight(1);

        QuestionNode mixedActiveLink = new QuestionNode();
        mixedActiveLink.setQuestionId(mixed.getId());
        mixedActiveLink.setNodeId(100L);
        mixedActiveLink.setWeight(1);

        KgNode active = new KgNode();
        active.setId(100L);
        active.setCourseId(COURSE_ID);
        active.setChapter("生效章节");
        active.setIsKey(1);

        stubComposeHappyPath(
                List.of(archivedOnly, mixed),
                List.of(archivedOnlyLink, mixedArchivedLink, mixedActiveLink),
                List.of(active));

        PaperVO paper = impl().composePaper(COURSE_ID);

        assertThat(paper.getQuestions())
                .extracting(PaperQuestionVO::getQuestionId)
                .containsExactly(mixed.getId());
        verify(diagnosticSessionQuestionMapper, times(1)).insert(any(DiagnosticSessionQuestion.class));
        verify(kgNodeMapper).selectList(any());
    }

    @Test
    @DisplayName("多 active 主节点：反转关联查询顺序仍按最小 nodeId 稳定组卷")
    void compose_multipleActiveMainNodes_usesSmallestNodeIdRegardlessOfMapperOrder() {
        Question multiNode = approved(701L);
        Question chapterAQuestion = approved(702L);
        Question chapterBQuestion = approved(703L);
        List<Question> questions = List.of(multiNode, chapterAQuestion, chapterBQuestion);

        KgNode chapterA = new KgNode();
        chapterA.setId(100L);
        chapterA.setCourseId(COURSE_ID);
        chapterA.setChapter("A章");
        chapterA.setIsKey(0);
        KgNode chapterB = new KgNode();
        chapterB.setId(200L);
        chapterB.setCourseId(COURSE_ID);
        chapterB.setChapter("B章");
        chapterB.setIsKey(0);

        QuestionNode multiA = new QuestionNode();
        multiA.setQuestionId(multiNode.getId());
        multiA.setNodeId(chapterA.getId());
        multiA.setWeight(1);
        QuestionNode multiB = new QuestionNode();
        multiB.setQuestionId(multiNode.getId());
        multiB.setNodeId(chapterB.getId());
        multiB.setWeight(1);
        QuestionNode onlyA = new QuestionNode();
        onlyA.setQuestionId(chapterAQuestion.getId());
        onlyA.setNodeId(chapterA.getId());
        onlyA.setWeight(1);
        QuestionNode onlyB = new QuestionNode();
        onlyB.setQuestionId(chapterBQuestion.getId());
        onlyB.setNodeId(chapterB.getId());
        onlyB.setWeight(1);

        List<QuestionNode> forward = List.of(multiA, multiB, onlyA, onlyB);
        List<QuestionNode> reversed = List.of(onlyB, onlyA, multiB, multiA);
        stubComposeHappyPath(questions, forward, List.of(chapterA, chapterB));
        when(questionNodeMapper.selectList(any())).thenReturn(forward, reversed);

        DiagnosticServiceImpl service = impl();
        ReflectionTestUtils.setField(service, "paperSize", 2);
        PaperVO first = service.composePaper(COURSE_ID);
        PaperVO second = service.composePaper(COURSE_ID);

        List<Long> firstIds = first.getQuestions().stream()
                .map(PaperQuestionVO::getQuestionId)
                .toList();
        List<Long> secondIds = second.getQuestions().stream()
                .map(PaperQuestionVO::getQuestionId)
                .toList();
        assertThat(firstIds).containsExactly(multiNode.getId(), chapterBQuestion.getId());
        assertThat(secondIds).isEqualTo(firstIds);
        assertThat(first.getQuestions().get(0).getChapter()).isEqualTo("A章");
        assertThat(second.getQuestions().get(0).getChapter()).isEqualTo("A章");
    }

    @Test
    @DisplayName("特性关闭 → compose 503")
    void compose_featureDisabled_503() {
        featureFlags.setFixedDiagnosticEnabled(false);
        assertThatThrownBy(() -> impl().composePaper(COURSE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
    }

    // ───────────────────── 攻击面：submit ─────────────────────

    @Test
    @DisplayName("攻击：会话外题目 → BAD_REQUEST")
    void submit_rejectsQuestionNotInFrozenPaper() {
        when(diagnosticSessionMapper.selectByTokenForUpdate(TOKEN))
                .thenReturn(inProgressSession(7L, STUDENT_ID, COURSE_ID));
        when(diagnosticSessionQuestionMapper.selectList(any()))
                .thenReturn(frozen(7L, 101L, 102L));

        assertThatThrownBy(() -> impl().submit(submitReq(List.of(
                ans(101L, "A"), ans(999L, "B")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("会话外题目");
        verify(masteryService, never()).applyAnswers(any(), any(), any());
    }

    @Test
    @DisplayName("攻击：重复 questionId → BAD_REQUEST")
    void submit_rejectsDuplicateQuestionIds() {
        when(diagnosticSessionMapper.selectByTokenForUpdate(TOKEN))
                .thenReturn(inProgressSession(7L, STUDENT_ID, COURSE_ID));
        when(diagnosticSessionQuestionMapper.selectList(any()))
                .thenReturn(frozen(7L, 101L, 102L));

        assertThatThrownBy(() -> impl().submit(submitReq(List.of(
                ans(101L, "A"), ans(101L, "B")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复");
        verify(masteryService, never()).applyAnswers(any(), any(), any());
    }

    @Test
    @DisplayName("攻击：跨课程题目 → BAD_REQUEST")
    void submit_rejectsCrossCourseQuestion() {
        when(diagnosticSessionMapper.selectByTokenForUpdate(TOKEN))
                .thenReturn(inProgressSession(7L, STUDENT_ID, COURSE_ID));
        when(diagnosticSessionQuestionMapper.selectList(any()))
                .thenReturn(frozen(7L, 101L));
        // CAS 成功前先校验题归属
        Question foreign = approved(101L);
        foreign.setCourseId(999L);
        when(questionMapper.selectList(any())).thenReturn(List.of(foreign));

        assertThatThrownBy(() -> impl().submit(submitReq(List.of(ans(101L, "A")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("跨课程");
        verify(masteryService, never()).applyAnswers(any(), any(), any());
    }

    @Test
    @DisplayName("攻击：未审核题目 → BAD_REQUEST")
    void submit_rejectsUnreviewedQuestion() {
        when(diagnosticSessionMapper.selectByTokenForUpdate(TOKEN))
                .thenReturn(inProgressSession(7L, STUDENT_ID, COURSE_ID));
        when(diagnosticSessionQuestionMapper.selectList(any()))
                .thenReturn(frozen(7L, 101L));
        Question pending = approved(101L);
        pending.setStatus(QuestionStatus.PENDING);
        when(questionMapper.selectList(any())).thenReturn(List.of(pending));

        assertThatThrownBy(() -> impl().submit(submitReq(List.of(ans(101L, "A")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未审核");
        verify(masteryService, never()).applyAnswers(any(), any(), any());
    }

    @Test
    @DisplayName("攻击：他人会话 → FORBIDDEN")
    void submit_rejectsOtherUsersSession() {
        when(diagnosticSessionMapper.selectByTokenForUpdate(TOKEN))
                .thenReturn(inProgressSession(7L, 999L, COURSE_ID));

        assertThatThrownBy(() -> impl().submit(submitReq(List.of(ans(101L, "A")))))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.FORBIDDEN.getCode());
        verify(masteryService, never()).applyAnswers(any(), any(), any());
    }

    @Test
    @DisplayName("幂等重放：完成后关闭开关仍返回 result_json，且不产生任何诊断写入")
    void submit_completedSession_replaysAfterFeatureDisabledWithoutWrites() throws Exception {
        SubmitResult stored = new SubmitResult();
        stored.setTotal(2);
        stored.setCorrectCount(1);
        QuestionGradeVO g = new QuestionGradeVO();
        g.setQuestionId(101L);
        g.setCorrect(true);
        g.setCorrectKey("A");
        stored.setGrades(List.of(g));

        DiagnosticSession done = inProgressSession(7L, STUDENT_ID, COURSE_ID);
        done.setStatus(1);
        done.setResultJson(objectMapper.writeValueAsString(stored));
        when(diagnosticSessionMapper.selectByTokenForUpdate(TOKEN)).thenReturn(done);
        featureFlags.setFixedDiagnosticEnabled(false);

        SubmitResult result = impl().submit(submitReq(List.of(ans(101L, "A"), ans(102L, "B"))));

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getCorrectCount()).isEqualTo(1);
        verify(diagnosticSessionMapper).selectByTokenForUpdate(TOKEN);
        verify(diagnosticSessionMapper, never()).update(any(), any());
        verify(diagnosticSessionQuestionMapper, never()).selectList(any());
        verify(questionMapper, never()).selectList(any());
        verify(questionOptionMapper, never()).selectList(any());
        verify(masteryService, never()).applyAnswers(any(), any(), any());
        verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
        verify(answerRecordMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("并发双提交：CAS 失败方走重放，不二次 applyAnswers")
    void submit_concurrentDoubleSubmit_onlyOneMasteryBooking() throws Exception {
        DiagnosticSession session = inProgressSession(7L, STUDENT_ID, COURSE_ID);
        when(diagnosticSessionMapper.selectByTokenForUpdate(TOKEN)).thenReturn(session);
        when(diagnosticSessionQuestionMapper.selectList(any()))
                .thenReturn(frozen(7L, 101L));
        when(questionMapper.selectList(any())).thenReturn(List.of(approved(101L)));

        // CAS 失败（另一线程已抢先）
        when(diagnosticSessionMapper.update(any(), any())).thenReturn(0);

        SubmitResult stored = new SubmitResult();
        stored.setTotal(1);
        stored.setCorrectCount(1);
        QuestionGradeVO g = new QuestionGradeVO();
        g.setQuestionId(101L);
        g.setCorrect(true);
        stored.setGrades(List.of(g));

        DiagnosticSession completed = inProgressSession(7L, STUDENT_ID, COURSE_ID);
        completed.setStatus(1);
        completed.setResultJson(objectMapper.writeValueAsString(stored));
        // 第二次 FOR UPDATE 返回已完成
        when(diagnosticSessionMapper.selectByTokenForUpdate(TOKEN))
                .thenReturn(session, completed);

        SubmitResult result = impl().submit(submitReq(List.of(ans(101L, "A"))));

        assertThat(result.getCorrectCount()).isEqualTo(1);
        verify(masteryService, never()).applyAnswers(any(), any(), any());
        verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
    }

    @Test
    @DisplayName("F 正常交卷：判分 + insert + applyAnswers 一次")
    void submitGradesAndInsertsAnswerRecords() {
        DiagnosticSession session = inProgressSession(7L, STUDENT_ID, COURSE_ID);
        when(diagnosticSessionMapper.selectByTokenForUpdate(TOKEN)).thenReturn(session);
        when(diagnosticSessionQuestionMapper.selectList(any()))
                .thenReturn(frozen(7L, 101L, 102L));
        when(questionMapper.selectList(any())).thenReturn(List.of(approved(101L), approved(102L)));
        when(diagnosticSessionMapper.update(any(), any())).thenReturn(1);

        QuestionOption opt101 = new QuestionOption();
        opt101.setQuestionId(101L);
        opt101.setOptionKey("A");
        opt101.setIsCorrect(1);
        QuestionOption opt102 = new QuestionOption();
        opt102.setQuestionId(102L);
        opt102.setOptionKey("B");
        opt102.setIsCorrect(1);
        when(questionOptionMapper.selectList(any())).thenReturn(List.of(opt101, opt102));

        SubmitResult result = impl().submit(submitReq(List.of(ans(101L, "A"), ans(102L, "C"))));

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getCorrectCount()).isEqualTo(1);

        ArgumentCaptor<AnswerRecord> captor = ArgumentCaptor.forClass(AnswerRecord.class);
        verify(answerRecordMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(r -> {
            assertThat(r.getFixedDiagnosticSessionId()).isEqualTo(7L);
            assertThat(r.getScene()).isEqualTo(1);
            assertThat(r.getStudentId()).isEqualTo(STUDENT_ID);
        });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GradedAnswer>> masteryCaptor = ArgumentCaptor.forClass(List.class);
        verify(masteryService, times(1)).applyAnswers(eq(STUDENT_ID), eq(COURSE_ID), masteryCaptor.capture());
        assertThat(masteryCaptor.getValue()).hasSize(2);

        verify(diagnosticSessionMapper, atLeastOnce()).update(any(), any());
    }

    @Test
    @DisplayName("缺 sessionToken → BAD_REQUEST")
    void submit_missingToken() {
        SubmitRequest req = new SubmitRequest();
        req.setStudentId(STUDENT_ID);
        req.setAnswers(List.of(ans(1L, "A")));
        assertThatThrownBy(() -> impl().submit(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sessionToken");
    }

    @Test
    @DisplayName("特性关闭 → submit 503")
    void submit_featureDisabled_503() {
        when(diagnosticSessionMapper.selectByTokenForUpdate(TOKEN))
                .thenReturn(inProgressSession(7L, STUDENT_ID, COURSE_ID));
        featureFlags.setFixedDiagnosticEnabled(false);
        assertThatThrownBy(() -> impl().submit(submitReq(List.of(ans(1L, "A")))))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        verify(diagnosticSessionMapper).selectByTokenForUpdate(TOKEN);
        verify(diagnosticSessionMapper, never()).update(any(), any());
        verify(diagnosticSessionQuestionMapper, never()).selectList(any());
        verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
        verify(masteryService, never()).applyAnswers(any(), any(), any());
    }

    @Test
    @DisplayName("部分提交（缺题）→ 精确集合失败")
    void submit_partialSet_rejected() {
        when(diagnosticSessionMapper.selectByTokenForUpdate(TOKEN))
                .thenReturn(inProgressSession(7L, STUDENT_ID, COURSE_ID));
        when(diagnosticSessionQuestionMapper.selectList(any()))
                .thenReturn(frozen(7L, 101L, 102L));

        assertThatThrownBy(() -> impl().submit(submitReq(List.of(ans(101L, "A")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("精确匹配");
    }
}
