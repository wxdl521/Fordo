package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.PracticeSession;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
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
import com.wenjin.support.QuestionStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PracticeServiceImpl 单元测试（TDD）。
 *
 * <p>所有 mapper 均 mock，无 Spring 上下文。覆盖：
 * <ul>
 *   <li>正常组卷返回正确结构</li>
 *   <li>未审核题绝不入卷</li>
 *   <li>7 天内答过的题排除（任意场景）</li>
 *   <li>0 题可用时抛 BusinessException</li>
 *   <li>question_ids 冻结值与返回题目列表一致（顺序和集合）</li>
 *   <li>size 超上限时夹紧到 maxSize</li>
 *   <li>size=null 时使用 defaultSize</li>
 *   <li>weight=1 不足时回退到 weight=2 补充</li>
 *   <li>weight=1 全无但 weight=2 有题时正常开会话（不足 size 放宽）</li>
 *   <li>全部近期已答 → 0 题 → BusinessException</li>
 *   <li>session 落库字段正确（studentId/courseId/nodeId/status/createdAt）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PracticeServiceImplTest {

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

    private static final Long STUDENT_ID = 2L;
    private static final Long COURSE_ID = 1L;
    private static final Long NODE_ID = 10L;

    // ── 构建 impl 实例（注入 @Value 字段） ─────────────────────────────────

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

    // ── fixture 工具方法 ─────────────────────────────────────────────────────

    private KgNode kgNode(Long id, String code, String name) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setNodeCode(code);
        n.setName(name);
        n.setCourseId(COURSE_ID);
        n.setChapter("第一章");
        return n;
    }

    private Question question(Long id, int status, int difficulty) {
        Question q = new Question();
        q.setId(id);
        q.setCourseId(COURSE_ID);
        q.setStem("题干" + id);
        q.setType(1);
        q.setDifficulty(difficulty);
        q.setStatus(status);
        q.setAnswer("A"); // 正确答案——绝不应出现在 VO 中
        return q;
    }

    private QuestionNode qnLink(Long questionId, Long nodeId, int weight) {
        QuestionNode qn = new QuestionNode();
        qn.setId(questionId * 100L + weight);
        qn.setQuestionId(questionId);
        qn.setNodeId(nodeId);
        qn.setWeight(weight);
        return qn;
    }

    private QuestionOption opt(Long questionId, String key, int isCorrect) {
        QuestionOption o = new QuestionOption();
        o.setId(questionId * 10L + key.charAt(0));
        o.setQuestionId(questionId);
        o.setOptionKey(key);
        o.setOptionText("选项" + key + "文本");
        o.setIsCorrect(isCorrect);
        o.setPointNodeCode(isCorrect == 0 ? "KT01" : null);
        return o;
    }

    private AnswerRecord recentRecord(Long questionId) {
        AnswerRecord ar = new AnswerRecord();
        ar.setStudentId(STUDENT_ID);
        ar.setQuestionId(questionId);
        ar.setAnsweredAt(LocalDateTime.now().minusDays(1));
        ar.setScene(1);
        return ar;
    }

    /** 为 practiceSessionMapper.insert 设置自增 ID 并返回 1（模拟 MyBatis-Plus insert） */
    private void stubInsertWithId(long sessionId) {
        when(practiceSessionMapper.insert(any(PracticeSession.class))).thenAnswer(inv -> {
            PracticeSession ps = inv.getArgument(0);
            ps.setId(sessionId);
            return 1;
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 1：正常组卷
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("正常组卷：返回 sessionId + node 信息 + 脱敏题目列表（≤ size）")
    void start_normalCase_returnsSessionWithDesensitizedQuestions() {
        // 8 道 weight=1, APPROVED 题，无近期答题记录
        List<QuestionNode> w1Links = new ArrayList<>();
        List<Question> approved = new ArrayList<>();
        List<QuestionOption> allOpts = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {
            w1Links.add(qnLink(i, NODE_ID, 1));
            approved.add(question(i, QuestionStatus.APPROVED, (int)(i % 4 + 1)));
            allOpts.add(opt(i, "A", 1));
            allOpts.add(opt(i, "B", 0));
        }

        when(kgNodeMapper.selectById(NODE_ID)).thenReturn(kgNode(NODE_ID, "KT01", "知识点1"));
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        when(questionNodeMapper.selectList(any())).thenReturn(w1Links);
        when(questionMapper.selectList(any())).thenReturn(approved);
        when(questionOptionMapper.selectList(any())).thenReturn(allOpts);
        stubInsertWithId(100L);

        PracticeStartVO vo = impl().start(STUDENT_ID, COURSE_ID, NODE_ID, 5);

        // sessionId
        assertThat(vo.getSessionId()).isEqualTo(100L);
        // node info
        assertThat(vo.getNode().getNodeId()).isEqualTo(NODE_ID);
        assertThat(vo.getNode().getNodeCode()).isEqualTo("KT01");
        assertThat(vo.getNode().getName()).isEqualTo("知识点1");
        // 题目数量 == requested size
        assertThat(vo.getQuestions()).hasSize(5);
        // 每道题有选项，且脱敏（OptionVO 只有 key + text）
        assertThat(vo.getQuestions()).allSatisfy(q -> {
            assertThat(q.getQuestionId()).isNotNull();
            assertThat(q.getStem()).isNotBlank();
            assertThat(q.getOptions()).isNotEmpty();
            q.getOptions().forEach(opt -> {
                assertThat(opt.getKey()).isNotBlank();
                assertThat(opt.getText()).isNotBlank();
            });
        });
        // session 被持久化
        verify(practiceSessionMapper).insert(any(PracticeSession.class));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 2：未审核题绝不入卷
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("未审核题（status=0 或 2）绝不入卷——只有 status=1 的题出现")
    void start_unapprovedQuestionsNeverSelected() {
        // question_node 有 3 条链接，但 question mapper 只返回已审核的那道
        List<QuestionNode> links = List.of(
                qnLink(1L, NODE_ID, 1),  // PENDING → 不入卷
                qnLink(2L, NODE_ID, 1),  // REJECTED → 不入卷
                qnLink(3L, NODE_ID, 1)); // APPROVED → 入卷

        when(kgNodeMapper.selectById(NODE_ID)).thenReturn(kgNode(NODE_ID, "KT01", "节点1"));
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        when(questionNodeMapper.selectList(any())).thenReturn(links);
        // 只返回 APPROVED 题（模拟 DB status=1 过滤）
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(3L, QuestionStatus.APPROVED, 3)));
        when(questionOptionMapper.selectList(any())).thenReturn(List.of(opt(3L, "A", 1), opt(3L, "B", 0)));
        stubInsertWithId(1L);

        PracticeStartVO vo = impl().start(STUDENT_ID, COURSE_ID, NODE_ID, 5);

        // 只有 1 道可用题（status=1）
        assertThat(vo.getQuestions()).hasSize(1);
        assertThat(vo.getQuestions().get(0).getQuestionId()).isEqualTo(3L);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 3：7 天内答过的题排除
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("7 天内答过的题（任意场景）被排除，仅返回未答过的")
    void start_recentlyAnsweredQuestionsExcluded() {
        // 6 道 approved weight=1 题
        List<QuestionNode> links = new ArrayList<>();
        List<Question> approved = new ArrayList<>();
        List<QuestionOption> opts = new ArrayList<>();
        for (long i = 1; i <= 6; i++) {
            links.add(qnLink(i, NODE_ID, 1));
            approved.add(question(i, QuestionStatus.APPROVED, 3));
            opts.add(opt(i, "A", 1));
        }

        // 题 1、2、3 在 7 天内已答
        List<AnswerRecord> recent = List.of(recentRecord(1L), recentRecord(2L), recentRecord(3L));

        when(kgNodeMapper.selectById(NODE_ID)).thenReturn(kgNode(NODE_ID, "KT01", "节点1"));
        when(answerRecordMapper.selectList(any())).thenReturn(recent);
        when(questionNodeMapper.selectList(any())).thenReturn(links);
        when(questionMapper.selectList(any())).thenReturn(approved);
        when(questionOptionMapper.selectList(any())).thenReturn(opts);
        stubInsertWithId(1L);

        PracticeStartVO vo = impl().start(STUDENT_ID, COURSE_ID, NODE_ID, 5);

        // 只剩题 4、5、6（排除了 1、2、3）
        assertThat(vo.getQuestions()).hasSize(3);
        List<Long> returnedIds = vo.getQuestions().stream()
                .map(PaperQuestionVO::getQuestionId)
                .collect(Collectors.toList());
        assertThat(returnedIds).doesNotContain(1L, 2L, 3L);
        assertThat(returnedIds).containsExactlyInAnyOrder(4L, 5L, 6L);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 4：0 题可用 → BusinessException
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("0 题可用时抛 BusinessException，提示题库不足")
    void start_zeroAvailableQuestions_throwsBusinessException() {
        when(kgNodeMapper.selectById(NODE_ID)).thenReturn(kgNode(NODE_ID, "KT01", "节点1"));
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        when(questionNodeMapper.selectList(any())).thenReturn(List.of());
        when(questionMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> impl().start(STUDENT_ID, COURSE_ID, NODE_ID, 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("题库不足");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 5：questionIds 冻结值与返回题目列表一致（顺序和集合）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("question_ids 冻结值与返回 questions 列表一致（顺序、集合双重验证）")
    void start_questionIdsFrozenConsistentWithReturnedList() {
        List<QuestionNode> links = List.of(qnLink(10L, NODE_ID, 1), qnLink(20L, NODE_ID, 1), qnLink(30L, NODE_ID, 1));
        List<Question> approved = List.of(
                question(10L, QuestionStatus.APPROVED, 2),
                question(20L, QuestionStatus.APPROVED, 3),
                question(30L, QuestionStatus.APPROVED, 4));

        when(kgNodeMapper.selectById(NODE_ID)).thenReturn(kgNode(NODE_ID, "KT01", "节点1"));
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        when(questionNodeMapper.selectList(any())).thenReturn(links);
        when(questionMapper.selectList(any())).thenReturn(approved);
        when(questionOptionMapper.selectList(any())).thenReturn(List.of());

        ArgumentCaptor<PracticeSession> captor = ArgumentCaptor.forClass(PracticeSession.class);
        when(practiceSessionMapper.insert(captor.capture())).thenAnswer(inv -> {
            PracticeSession ps = inv.getArgument(0);
            ps.setId(99L);
            return 1;
        });

        PracticeStartVO vo = impl().start(STUDENT_ID, COURSE_ID, NODE_ID, 5);

        PracticeSession saved = captor.getValue();
        String[] frozenIds = saved.getQuestionIds().split(",");
        List<Long> returnedIds = vo.getQuestions().stream()
                .map(PaperQuestionVO::getQuestionId)
                .collect(Collectors.toList());

        // 集合一致
        assertThat(frozenIds).hasSize(returnedIds.size());
        // 顺序一致
        for (int i = 0; i < frozenIds.length; i++) {
            assertThat(Long.parseLong(frozenIds[i].trim())).isEqualTo(returnedIds.get(i));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 6：size 超上限夹紧到 maxSize
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("size 超 maxSize=10 时夹紧，返回 10 道题")
    void start_sizeExceedsMaxSize_clampedToMaxSize() {
        List<QuestionNode> links = new ArrayList<>();
        List<Question> approved = new ArrayList<>();
        for (long i = 1; i <= 15; i++) {
            links.add(qnLink(i, NODE_ID, 1));
            approved.add(question(i, QuestionStatus.APPROVED, (int)(i % 4 + 1)));
        }

        when(kgNodeMapper.selectById(NODE_ID)).thenReturn(kgNode(NODE_ID, "KT01", "节点1"));
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        when(questionNodeMapper.selectList(any())).thenReturn(links);
        when(questionMapper.selectList(any())).thenReturn(approved);
        when(questionOptionMapper.selectList(any())).thenReturn(List.of());
        stubInsertWithId(1L);

        PracticeStartVO vo = impl().start(STUDENT_ID, COURSE_ID, NODE_ID, 99);

        assertThat(vo.getQuestions()).hasSize(10);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 7：size=null 默认使用 defaultSize=5
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("size=null 时使用默认题数 5")
    void start_sizeNull_usesDefaultSize() {
        List<QuestionNode> links = new ArrayList<>();
        List<Question> approved = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            links.add(qnLink(i, NODE_ID, 1));
            approved.add(question(i, QuestionStatus.APPROVED, 3));
        }

        when(kgNodeMapper.selectById(NODE_ID)).thenReturn(kgNode(NODE_ID, "KT01", "节点1"));
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        when(questionNodeMapper.selectList(any())).thenReturn(links);
        when(questionMapper.selectList(any())).thenReturn(approved);
        when(questionOptionMapper.selectList(any())).thenReturn(List.of());
        stubInsertWithId(1L);

        PracticeStartVO vo = impl().start(STUDENT_ID, COURSE_ID, NODE_ID, null);

        assertThat(vo.getQuestions()).hasSize(5);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 8：weight=1 不足 size → 回退到 weight=2
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("weight=1 不足 size 时，回退到 weight=2 补充，最终凑满 size")
    void start_fallbackToWeight2WhenWeight1Insufficient() {
        // weight=1：仅 2 题
        List<QuestionNode> w1Links = List.of(qnLink(1L, NODE_ID, 1), qnLink(2L, NODE_ID, 1));
        List<Question> w1Approved = List.of(
                question(1L, QuestionStatus.APPROVED, 2),
                question(2L, QuestionStatus.APPROVED, 3));

        // weight=2：5 题（可补充）
        List<QuestionNode> w2Links = List.of(
                qnLink(10L, NODE_ID, 2), qnLink(11L, NODE_ID, 2), qnLink(12L, NODE_ID, 2),
                qnLink(13L, NODE_ID, 2), qnLink(14L, NODE_ID, 2));
        List<Question> w2Approved = List.of(
                question(10L, QuestionStatus.APPROVED, 4),
                question(11L, QuestionStatus.APPROVED, 3),
                question(12L, QuestionStatus.APPROVED, 2),
                question(13L, QuestionStatus.APPROVED, 3),
                question(14L, QuestionStatus.APPROVED, 4));

        when(kgNodeMapper.selectById(NODE_ID)).thenReturn(kgNode(NODE_ID, "KT01", "节点1"));
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        // 第一次调用 questionNodeMapper → w1 links；第二次 → w2 links
        when(questionNodeMapper.selectList(any())).thenReturn(w1Links).thenReturn(w2Links);
        // 第一次调用 questionMapper → w1 approved；第二次 → w2 approved
        when(questionMapper.selectList(any())).thenReturn(w1Approved).thenReturn(w2Approved);
        when(questionOptionMapper.selectList(any())).thenReturn(List.of());
        stubInsertWithId(1L);

        PracticeStartVO vo = impl().start(STUDENT_ID, COURSE_ID, NODE_ID, 5);

        assertThat(vo.getQuestions()).hasSize(5);
        // weight=1 题（id 1, 2）一定被包含（先加入 pool）
        List<Long> ids = vo.getQuestions().stream()
                .map(PaperQuestionVO::getQuestionId)
                .collect(Collectors.toList());
        assertThat(ids).contains(1L, 2L);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 9：只有 weight=2 题，有多少出多少（不足 size 放宽）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("weight=1 全无，仅 weight=2 有 3 题，不足 size 放宽，正常开会话")
    void start_onlyWeight2Available_succeedsWithFewerThanSize() {
        List<QuestionNode> w2Links = List.of(
                qnLink(20L, NODE_ID, 2), qnLink(21L, NODE_ID, 2), qnLink(22L, NODE_ID, 2));
        List<Question> w2Approved = List.of(
                question(20L, QuestionStatus.APPROVED, 3),
                question(21L, QuestionStatus.APPROVED, 4),
                question(22L, QuestionStatus.APPROVED, 2));

        when(kgNodeMapper.selectById(NODE_ID)).thenReturn(kgNode(NODE_ID, "KT01", "节点1"));
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        when(questionNodeMapper.selectList(any()))
                .thenReturn(List.of())   // weight=1：空（early-return，不调 questionMapper）
                .thenReturn(w2Links);    // weight=2：3 题
        // weight=1 links 为空时 fetchApprovedPool 提前返回，questionMapper 只被调用一次（weight=2）
        when(questionMapper.selectList(any()))
                .thenReturn(w2Approved);
        when(questionOptionMapper.selectList(any())).thenReturn(List.of());
        stubInsertWithId(1L);

        PracticeStartVO vo = impl().start(STUDENT_ID, COURSE_ID, NODE_ID, 5);

        // 只有 3 道可用（< size），但 ≥ 1，应正常开会话
        assertThat(vo.getQuestions()).hasSize(3);
        List<Long> ids = vo.getQuestions().stream()
                .map(PaperQuestionVO::getQuestionId)
                .collect(Collectors.toList());
        assertThat(ids).containsExactlyInAnyOrder(20L, 21L, 22L);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 10：所有题都被近期答题排除 → 0 题 → BusinessException
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("weight=1 + weight=2 全被近期答题排除后 0 题可用 → BusinessException")
    void start_allRecentlyAnswered_throwsBusinessException() {
        List<QuestionNode> w1Links = List.of(qnLink(1L, NODE_ID, 1), qnLink(2L, NODE_ID, 1));
        List<Question> w1Approved = List.of(
                question(1L, QuestionStatus.APPROVED, 3),
                question(2L, QuestionStatus.APPROVED, 3));

        // 题 1、2 都在 7 天内答过
        List<AnswerRecord> recent = List.of(recentRecord(1L), recentRecord(2L));

        when(kgNodeMapper.selectById(NODE_ID)).thenReturn(kgNode(NODE_ID, "KT01", "节点1"));
        when(answerRecordMapper.selectList(any())).thenReturn(recent);
        when(questionNodeMapper.selectList(any()))
                .thenReturn(w1Links)      // weight=1
                .thenReturn(List.of());   // weight=2：无
        when(questionMapper.selectList(any()))
                .thenReturn(w1Approved)   // weight=1 approved（但都近期答过）
                .thenReturn(List.of());   // weight=2 approved：无

        assertThatThrownBy(() -> impl().start(STUDENT_ID, COURSE_ID, NODE_ID, 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("题库不足");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 11：session 落库字段验证
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("practice_session 落库字段正确：studentId/courseId/nodeId/status=0/createdAt 非空/submittedAt 为 null")
    void start_sessionPersistedWithCorrectFields() {
        when(kgNodeMapper.selectById(NODE_ID)).thenReturn(kgNode(NODE_ID, "KT01", "节点1"));
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(qnLink(1L, NODE_ID, 1)));
        when(questionMapper.selectList(any())).thenReturn(List.of(question(1L, QuestionStatus.APPROVED, 3)));
        when(questionOptionMapper.selectList(any())).thenReturn(List.of());

        ArgumentCaptor<PracticeSession> captor = ArgumentCaptor.forClass(PracticeSession.class);
        when(practiceSessionMapper.insert(captor.capture())).thenAnswer(inv -> {
            PracticeSession ps = inv.getArgument(0);
            ps.setId(42L);
            return 1;
        });

        PracticeStartVO vo = impl().start(STUDENT_ID, COURSE_ID, NODE_ID, 5);

        PracticeSession saved = captor.getValue();
        assertThat(saved.getStudentId()).isEqualTo(STUDENT_ID);
        assertThat(saved.getCourseId()).isEqualTo(COURSE_ID);
        assertThat(saved.getNodeId()).isEqualTo(NODE_ID);
        assertThat(saved.getStatus()).isEqualTo(0);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getSubmittedAt()).isNull();
        assertThat(saved.getQuestionIds()).isEqualTo("1"); // 仅 1 道题
        assertThat(vo.getSessionId()).isEqualTo(42L);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 12：难度分层——优先覆盖 2–4
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("轻度难度分层：pool 包含难度 1/2/3/4/5 各 2 题，size=3 时优先选 diff=2/3/4 各 1")
    void start_difficultyStratification_preferMidRange() {
        // 10 道题，难度 1/2/3/4/5 各 2 道
        List<QuestionNode> links = new ArrayList<>();
        List<Question> approved = new ArrayList<>();
        for (int diff = 1; diff <= 5; diff++) {
            for (int j = 1; j <= 2; j++) {
                long qid = diff * 10L + j;
                links.add(qnLink(qid, NODE_ID, 1));
                approved.add(question(qid, QuestionStatus.APPROVED, diff));
            }
        }

        when(kgNodeMapper.selectById(NODE_ID)).thenReturn(kgNode(NODE_ID, "KT01", "节点1"));
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        when(questionNodeMapper.selectList(any())).thenReturn(links);
        when(questionMapper.selectList(any())).thenReturn(approved);
        when(questionOptionMapper.selectList(any())).thenReturn(List.of());
        stubInsertWithId(1L);

        PracticeStartVO vo = impl().start(STUDENT_ID, COURSE_ID, NODE_ID, 3);

        assertThat(vo.getQuestions()).hasSize(3);
        // 3 道题的难度应都在 [2,4] 范围内（优先选策略）
        List<Integer> difficulties = vo.getQuestions().stream()
                .map(q -> approved.stream().filter(a -> a.getId().equals(q.getQuestionId()))
                        .findFirst().map(Question::getDifficulty).orElse(-1))
                .collect(Collectors.toList());
        assertThat(difficulties).allSatisfy(d -> assertThat(d).isBetween(2, 4));
    }
}
