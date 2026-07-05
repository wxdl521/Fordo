package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.dto.AdaptiveAnswerRequest;
import com.wenjin.dto.AdaptiveDiagnosticVO;
import com.wenjin.dto.GradedAnswer;
import com.wenjin.entity.AdaptiveDiagSession;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.AdaptiveDiagSessionMapper;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.service.impl.AdaptiveDiagnosticServiceImpl;
import com.wenjin.support.AdaptiveDiagState;
import com.wenjin.support.NodeState;
import com.wenjin.support.PrereqDirection;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdaptiveDiagnosticService 单元测试（M2 T4 TDD，Mockito 风格对齐 PracticeSubmitTest）。
 *
 * <p>覆盖简报测试清单：start 落库+首探针+脱敏+进度、start 即终止、answer 判分+传播落盘+
 * applyAnswers 恰一次+answer_record scene=1+预算记账+下一探针≠已测、探针外题目拒绝、
 * 幂等重放、终止收尾（done+status=1）、属主/状态防线、abandon 三态、1 题探针。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdaptiveDiagnosticServiceTest {

    @Mock KgNodeMapper kgNodeMapper;
    @Mock KgEdgeMapper kgEdgeMapper;
    @Mock QuestionNodeMapper questionNodeMapper;
    @Mock QuestionMapper questionMapper;
    @Mock QuestionOptionMapper questionOptionMapper;
    @Mock AnswerRecordMapper answerRecordMapper;
    @Mock AdaptiveDiagSessionMapper adaptiveDiagSessionMapper;
    @Mock MasteryService masteryService;
    @Mock StudentMasteryMapper studentMasteryMapper;

    private static final Long STUDENT_ID = 2L;
    private static final Long COURSE_ID = 1L;
    private static final Long SESSION_ID = 500L;
    private static final int BUDGET = 15;

    private AdaptiveDiagnosticServiceImpl impl() {
        AdaptiveDiagnosticServiceImpl impl = new AdaptiveDiagnosticServiceImpl(
                kgNodeMapper, kgEdgeMapper, questionNodeMapper, questionMapper,
                questionOptionMapper, answerRecordMapper, adaptiveDiagSessionMapper,
                masteryService, studentMasteryMapper);
        ReflectionTestUtils.setField(impl, "budget", BUDGET);
        ReflectionTestUtils.setField(impl, "recencyDays", 7);
        return impl;
    }

    /**
     * 默认 CAS 写回成功（answer 走 {@code update(session, WHERE status=0 AND state_json=旧)} 影响 1 行），
     * 使正常作答流程不落"并发抢占失败"分支；需模拟抢占失败的用例显式覆盖为 {@code thenReturn(0)}。
     * 与 M1 {@code PracticeSubmitTest} 的 CAS 默认 stub 同款。
     */
    @BeforeEach
    void stubCasSuccess() {
        when(adaptiveDiagSessionMapper.update(any(), any())).thenReturn(1);
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private KgNode node(long id) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setCourseId(COURSE_ID);
        n.setNodeCode("KT" + id);
        n.setName("节点" + id);
        n.setIsKey(0);
        return n;
    }

    /** 前置边：from 是 to 的前置（T1 钉死方向）。 */
    private KgEdge prereq(long from, long to) {
        KgEdge e = new KgEdge();
        e.setFromNodeId(from);
        e.setToNodeId(to);
        e.setRelationType(PrereqDirection.RELATION_TYPE_PREREQUISITE);
        return e;
    }

    private Question q(long id, int type) {
        Question q = new Question();
        q.setId(id);
        q.setCourseId(COURSE_ID);
        q.setStem("题干" + id);
        q.setType(type);
        q.setDifficulty(3);
        q.setStatus(1); // APPROVED
        q.setAnswer("A"); // 绝不应出现在探针 VO 中
        return q;
    }

    private QuestionNode link(long questionId, long nodeId, int weight) {
        QuestionNode qn = new QuestionNode();
        qn.setId(questionId * 100 + weight);
        qn.setQuestionId(questionId);
        qn.setNodeId(nodeId);
        qn.setWeight(weight);
        return qn;
    }

    private QuestionOption opt(long questionId, String key, int isCorrect) {
        QuestionOption o = new QuestionOption();
        o.setId(questionId * 10 + key.charAt(0));
        o.setQuestionId(questionId);
        o.setOptionKey(key);
        o.setOptionText("选项" + key);
        o.setIsCorrect(isCorrect);
        o.setPointNodeCode(isCorrect == 0 ? "KT99" : null); // 绝不应泄露
        return o;
    }

    private AdaptiveAnswerRequest req(Long studentId, AnswerPair... pairs) {
        AdaptiveAnswerRequest r = new AdaptiveAnswerRequest();
        r.setStudentId(studentId);
        List<AdaptiveAnswerRequest.AnswerItem> items = new ArrayList<>();
        for (AnswerPair p : pairs) {
            AdaptiveAnswerRequest.AnswerItem it = new AdaptiveAnswerRequest.AnswerItem();
            it.setQuestionId(p.qid);
            it.setStudentAnswer(p.answer);
            items.add(it);
        }
        r.setAnswers(items);
        return r;
    }

    private record AnswerPair(Long qid, String answer) {}

    private AnswerPair a(Long qid, String answer) {
        return new AnswerPair(qid, answer);
    }

    private AdaptiveDiagSession session(int status, String stateJson) {
        AdaptiveDiagSession s = new AdaptiveDiagSession();
        s.setId(SESSION_ID);
        s.setStudentId(STUDENT_ID);
        s.setCourseId(COURSE_ID);
        s.setStatus(status);
        s.setStateJson(stateJson);
        return s;
    }

    private String stateJson(Map<Long, NodeState> states, int budgetUsed,
                             AdaptiveDiagState.Probe probe,
                             List<AdaptiveDiagState.ProbeRecord> history) {
        return new AdaptiveDiagState(states, budgetUsed, probe, history).toJson();
    }

    /** 从 insert/updateById 捕获的 session 解析出状态快照。 */
    private AdaptiveDiagState parse(AdaptiveDiagSession s) {
        return AdaptiveDiagState.fromJson(s.getStateJson());
    }

    private void stubInsertWithId(long id) {
        when(adaptiveDiagSessionMapper.insert(any(AdaptiveDiagSession.class))).thenAnswer(inv -> {
            AdaptiveDiagSession s = inv.getArgument(0);
            s.setId(id);
            return 1;
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // start 用例 1：正常开局——会话落库 + 首探针 + 脱敏 + 进度
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("start 正常：建会话(全 UNKNOWN + 首探针冻结)、首探针=引擎选点(nodeId=10)、题目脱敏+仅客观、budgetLeft=15")
    void start_normal_persistsSessionAndFirstProbe() {
        // 图：两孤立可实测节点 10、20；无边；平手裁决取最小 nodeId=10
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(10), node(20)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());

        // computeTestable: 全课程 question_node 链接 + APPROVED 客观题
        List<QuestionNode> allLinks = List.of(
                link(101, 10, 1), link(102, 10, 1), link(103, 10, 1),
                link(201, 20, 1), link(202, 20, 1));
        List<Question> allApproved = List.of(
                q(101, QuestionType.SINGLE), q(102, QuestionType.SINGLE),
                q(103, QuestionType.SHORT_ANSWER), // 简答：不计入可实测、不入探针
                q(201, QuestionType.SINGLE), q(202, QuestionType.SINGLE));
        // picker weight=1 for node 10（含一道简答，应被客观过滤剔除）
        List<QuestionNode> node10W1 = List.of(link(101, 10, 1), link(102, 10, 1), link(103, 10, 1));
        List<Question> node10Approved = List.of(
                q(101, QuestionType.SINGLE), q(102, QuestionType.SINGLE), q(103, QuestionType.SHORT_ANSWER));

        when(questionNodeMapper.selectList(any())).thenReturn(allLinks).thenReturn(node10W1);
        when(questionMapper.selectList(any())).thenReturn(allApproved).thenReturn(node10Approved);
        when(answerRecordMapper.selectList(any())).thenReturn(List.of()); // 无近期
        when(questionOptionMapper.selectList(any())).thenReturn(List.of(
                opt(101, "A", 1), opt(101, "B", 0), opt(102, "A", 1), opt(102, "B", 0)));
        stubInsertWithId(SESSION_ID);

        AdaptiveDiagnosticVO vo = impl().start(STUDENT_ID, COURSE_ID);

        assertThat(vo.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(vo.isDone()).isFalse();
        // 首探针 = 节点 10，题目仅客观（简答 103 被剔除），脱敏无 isCorrect/pointNodeCode
        assertThat(vo.getProbe()).isNotNull();
        assertThat(vo.getProbe().getNodeId()).isEqualTo(10L);
        List<Long> probeQids = vo.getProbe().getQuestions().stream()
                .map(pq -> pq.getQuestionId()).collect(Collectors.toList());
        assertThat(probeQids).containsExactly(101L, 102L);
        assertThat(probeQids).doesNotContain(103L);
        vo.getProbe().getQuestions().forEach(pq ->
                pq.getOptions().forEach(o -> {
                    assertThat(o.getKey()).isNotBlank();
                    assertThat(o.getText()).isNotBlank();
                }));
        // 进度：0 测 0 推 2 未知，预算满 15
        assertThat(vo.getProgress().getTested()).isZero();
        assertThat(vo.getProgress().getInferred()).isZero();
        assertThat(vo.getProgress().getUnknown()).isEqualTo(2);
        assertThat(vo.getProgress().getBudgetLeft()).isEqualTo(15);
        assertThat(vo.getSummary()).isNull();

        // 会话落库：status=0、state_json 全 UNKNOWN + 首探针冻结
        ArgumentCaptor<AdaptiveDiagSession> cap = ArgumentCaptor.forClass(AdaptiveDiagSession.class);
        verify(adaptiveDiagSessionMapper).insert(cap.capture());
        AdaptiveDiagSession saved = cap.getValue();
        assertThat(saved.getStatus()).isEqualTo(0);
        assertThat(saved.getCreatedAt()).isNotNull();
        AdaptiveDiagState st = parse(saved);
        assertThat(st.nodeStates()).containsEntry(10L, NodeState.UNKNOWN)
                .containsEntry(20L, NodeState.UNKNOWN);
        assertThat(st.budgetUsed()).isZero();
        assertThat(st.currentProbe().nodeId()).isEqualTo(10L);
        assertThat(st.currentProbe().questionIds()).containsExactly(101L, 102L);
        assertThat(st.probeHistory()).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // start 用例 2：一开局即终止（无可实测节点）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("start 即终止：testable 为空 → done=true、probe=null、会话 status=1、summary 四类计数")
    void start_terminatesImmediately_whenNoTestable() {
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(10), node(20)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        // 无 question_node 链接 → computeTestable 返回空 → 引擎 NO_TESTABLE 终止
        when(questionNodeMapper.selectList(any())).thenReturn(List.of());
        stubInsertWithId(SESSION_ID);

        AdaptiveDiagnosticVO vo = impl().start(STUDENT_ID, COURSE_ID);

        assertThat(vo.isDone()).isTrue();
        assertThat(vo.getProbe()).isNull();
        assertThat(vo.getSummary()).isNotNull();
        assertThat(vo.getSummary().getUnknown()).isEqualTo(2);
        assertThat(vo.getSummary().getTested()).isZero();
        // T5：空图（全 UNKNOWN）终止 → nodes 含所有节点（UNKNOWN 态），非空（空图 = 空列表仅指无节点时）
        assertThat(vo.getSummary().getNodes()).hasSize(2);
        assertThat(vo.getSummary().getNodes()).allMatch(nd -> "UNKNOWN".equals(nd.getState()));
        assertThat(vo.getSummary().getNodes().get(0).getNodeId()).isEqualTo(10L);
        assertThat(vo.getSummary().getNodes().get(1).getNodeId()).isEqualTo(20L);
        assertThat(vo.getResultUrl()).contains("courseId=" + COURSE_ID);
        assertThat(vo.getProgress().getBudgetLeft()).isEqualTo(15);

        ArgumentCaptor<AdaptiveDiagSession> cap = ArgumentCaptor.forClass(AdaptiveDiagSession.class);
        verify(adaptiveDiagSessionMapper).insert(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(1);
        assertThat(cap.getValue().getFinishedAt()).isNotNull();
        // 从未组探针 → 未查题库
        verify(masteryService, never()).applyAnswers(any(), any(), any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // answer 用例 3a：正常判分 PASS 传播落盘 + applyAnswers 恰一次 + answer_record scene=1
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("answer 全对：TESTED_PASS + 前置祖先 INFERRED_PASS 落盘、applyAnswers 恰一次、answer_record scene=1、done(NO_TESTABLE)")
    void answer_allCorrect_propagatesPassAndPersists() {
        // 链 1→2→3；探针节点 2；仅节点 2 可实测（1、3 无题）→ 记录后 NO_TESTABLE 终止
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(1L, NodeState.UNKNOWN, 2L, NodeState.UNKNOWN, 3L, NodeState.UNKNOWN)),
                0,
                new AdaptiveDiagState.Probe(2L, List.of(21L, 22L)),
                List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));

        // 判分：q21、q22 均正确项 A
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(q(21, QuestionType.SINGLE), q(22, QuestionType.SINGLE))) // 判分
                .thenReturn(List.of(q(21, QuestionType.SINGLE), q(22, QuestionType.SINGLE))); // testable
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(opt(21, "A", 1), opt(22, "A", 1))); // 判分正确键
        // testable：仅节点 2 有 APPROVED 客观题
        when(questionNodeMapper.selectList(any()))
                .thenReturn(List.of(link(21, 2, 1), link(22, 2, 1)));
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1), node(2), node(3)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 2), prereq(2, 3)));

        AdaptiveDiagnosticVO vo = impl().answer(SESSION_ID, req(STUDENT_ID, a(21L, "A"), a(22L, "A")));

        // 终止：done + summary
        assertThat(vo.isDone()).isTrue();
        assertThat(vo.getSummary().getTested()).isEqualTo(1);
        assertThat(vo.getSummary().getInferredPass()).isEqualTo(1);
        assertThat(vo.getProgress().getTested()).isEqualTo(1);
        assertThat(vo.getProgress().getInferred()).isEqualTo(1);
        assertThat(vo.getProgress().getUnknown()).isEqualTo(1);
        assertThat(vo.getProgress().getBudgetLeft()).isEqualTo(13); // 15 - 2

        // applyAnswers 恰一次，内容正确
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GradedAnswer>> gc = ArgumentCaptor.forClass(List.class);
        verify(masteryService).applyAnswers(
                org.mockito.ArgumentMatchers.eq(STUDENT_ID),
                org.mockito.ArgumentMatchers.eq(COURSE_ID),
                gc.capture());
        assertThat(gc.getValue()).hasSize(2);
        assertThat(gc.getValue()).allMatch(GradedAnswer::isCorrect);

        // answer_record 每题一条 scene=1 + session_id
        ArgumentCaptor<AnswerRecord> rc = ArgumentCaptor.forClass(AnswerRecord.class);
        verify(answerRecordMapper, org.mockito.Mockito.times(2)).insert(rc.capture());
        rc.getAllValues().forEach(r -> {
            assertThat(r.getScene()).isEqualTo(1);
            assertThat(r.getSessionId()).isEqualTo(SESSION_ID);
            assertThat(r.getStudentId()).isEqualTo(STUDENT_ID);
        });

        // 状态落盘：node2=TESTED_PASS、node1=INFERRED_PASS(祖先)、node3=UNKNOWN、status=1
        ArgumentCaptor<AdaptiveDiagSession> sc = ArgumentCaptor.forClass(AdaptiveDiagSession.class);
        verify(adaptiveDiagSessionMapper).update(sc.capture(), any());
        assertThat(sc.getValue().getStatus()).isEqualTo(1);
        AdaptiveDiagState after = parse(sc.getValue());
        assertThat(after.nodeStates().get(2L)).isEqualTo(NodeState.TESTED_PASS);
        assertThat(after.nodeStates().get(1L)).isEqualTo(NodeState.INFERRED_PASS);
        assertThat(after.nodeStates().get(3L)).isEqualTo(NodeState.UNKNOWN);
        assertThat(after.currentProbe()).isNull();
        assertThat(after.probeHistory()).hasSize(1);
        assertThat(after.probeHistory().get(0).pass()).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // answer 用例 3b：有错 → FAIL 传播（后继子孙 INFERRED_FAIL）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("answer 有错：TESTED_FAIL + 后继子孙 INFERRED_FAIL 落盘")
    void answer_hasWrong_propagatesFail() {
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(1L, NodeState.UNKNOWN, 2L, NodeState.UNKNOWN, 3L, NodeState.UNKNOWN)),
                0,
                new AdaptiveDiagState.Probe(2L, List.of(21L, 22L)),
                List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(q(21, QuestionType.SINGLE), q(22, QuestionType.SINGLE)))
                .thenReturn(List.of(q(21, QuestionType.SINGLE), q(22, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(opt(21, "A", 1), opt(22, "A", 1)));
        when(questionNodeMapper.selectList(any()))
                .thenReturn(List.of(link(21, 2, 1), link(22, 2, 1)));
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1), node(2), node(3)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 2), prereq(2, 3)));

        // q22 答错（B ≠ A）
        AdaptiveDiagnosticVO vo = impl().answer(SESSION_ID, req(STUDENT_ID, a(21L, "A"), a(22L, "B")));

        ArgumentCaptor<AdaptiveDiagSession> sc = ArgumentCaptor.forClass(AdaptiveDiagSession.class);
        verify(adaptiveDiagSessionMapper).update(sc.capture(), any());
        AdaptiveDiagState after = parse(sc.getValue());
        assertThat(after.nodeStates().get(2L)).isEqualTo(NodeState.TESTED_FAIL);
        assertThat(after.nodeStates().get(3L)).isEqualTo(NodeState.INFERRED_FAIL); // 子孙
        assertThat(after.nodeStates().get(1L)).isEqualTo(NodeState.UNKNOWN);       // 祖先不受影响
        assertThat(after.probeHistory().get(0).pass()).isFalse();
        assertThat(vo.getSummary().getInferredFail()).isEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // answer 用例 3c：未终止 → 返回下一探针，nodeId ≠ 已测节点，预算记账
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("answer 未终止：返回下一探针(nodeId=20 ≠ 已测 10)、budgetLeft=13、状态含 node10=TESTED_PASS")
    void answer_notTerminated_returnsNextProbe() {
        // 两孤立可实测节点 10、20；探针 10；记录后选下一探针 20
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(10L, NodeState.UNKNOWN, 20L, NodeState.UNKNOWN)),
                0,
                new AdaptiveDiagState.Probe(10L, List.of(101L, 102L)),
                List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));

        when(questionMapper.selectList(any()))
                .thenReturn(List.of(q(101, QuestionType.SINGLE), q(102, QuestionType.SINGLE))) // 判分
                .thenReturn(List.of(q(101, QuestionType.SINGLE), q(102, QuestionType.SINGLE),
                        q(201, QuestionType.SINGLE), q(202, QuestionType.SINGLE)))              // testable
                .thenReturn(List.of(q(201, QuestionType.SINGLE), q(202, QuestionType.SINGLE))); // picker node20
        when(questionNodeMapper.selectList(any()))
                .thenReturn(List.of(link(101, 10, 1), link(102, 10, 1),
                        link(201, 20, 1), link(202, 20, 1))) // testable
                .thenReturn(List.of(link(201, 20, 1), link(202, 20, 1))); // picker node20 w1
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(opt(101, "A", 1), opt(102, "A", 1)))  // 判分
                .thenReturn(List.of(opt(201, "A", 1), opt(202, "A", 1))); // 脱敏 node20
        when(answerRecordMapper.selectList(any())).thenReturn(List.of()); // recency
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(10), node(20)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());

        AdaptiveDiagnosticVO vo = impl().answer(SESSION_ID, req(STUDENT_ID, a(101L, "A"), a(102L, "A")));

        assertThat(vo.isDone()).isFalse();
        assertThat(vo.getProbe()).isNotNull();
        assertThat(vo.getProbe().getNodeId()).isEqualTo(20L); // 下一探针
        assertThat(vo.getProbe().getNodeId()).isNotEqualTo(10L); // ≠ 已测
        assertThat(vo.getProbe().getQuestions()).hasSize(2);
        assertThat(vo.getProgress().getTested()).isEqualTo(1);
        assertThat(vo.getProgress().getBudgetLeft()).isEqualTo(13); // 15 - 2

        ArgumentCaptor<AdaptiveDiagSession> sc = ArgumentCaptor.forClass(AdaptiveDiagSession.class);
        verify(adaptiveDiagSessionMapper).update(sc.capture(), any());
        AdaptiveDiagState after = parse(sc.getValue());
        assertThat(after.nodeStates().get(10L)).isEqualTo(NodeState.TESTED_PASS);
        assertThat(after.currentProbe().nodeId()).isEqualTo(20L);
        assertThat(after.budgetUsed()).isEqualTo(2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // answer 用例 3d：并发抢占（CAS 影响 0 行）→ 抛异常传播（回滚由 @Transactional 保证）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("answer 并发双击：CAS update 影响 0 行 → 抛 BusinessException(400) 传播触发回滚；本调用 applyAnswers 恰一次(非双施)、CAS 恰尝试一次")
    void answer_concurrentCasLost_throwsForRollback() {
        // 与 3a 同构：链 1→2→3，探针节点 2 全对；但另一并发在途请求已先行推进 state_json，
        // 本请求写回 CAS(WHERE status=0 AND state_json=旧) 匹配 0 行 → 抛异常，交由事务回滚本次已写入。
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(1L, NodeState.UNKNOWN, 2L, NodeState.UNKNOWN, 3L, NodeState.UNKNOWN)),
                0,
                new AdaptiveDiagState.Probe(2L, List.of(21L, 22L)),
                List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(q(21, QuestionType.SINGLE), q(22, QuestionType.SINGLE)))
                .thenReturn(List.of(q(21, QuestionType.SINGLE), q(22, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(opt(21, "A", 1), opt(22, "A", 1)));
        when(questionNodeMapper.selectList(any()))
                .thenReturn(List.of(link(21, 2, 1), link(22, 2, 1)));
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1), node(2), node(3)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 2), prereq(2, 3)));
        // 关键：CAS 抢占失败（覆盖 @BeforeEach 默认 thenReturn(1)）——另一线程已推进 state_json
        when(adaptiveDiagSessionMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> impl().answer(SESSION_ID, req(STUDENT_ID, a(21L, "A"), a(22L, "A"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));

        // 本次调用内 applyAnswers 恰一次（非双施）；异常传播后由 @Transactional 回滚这次写入。
        // CAS 条件更新确被尝试一次（影响 0 行才抛）——证明写回走的是条件更新而非无条件 updateById。
        verify(masteryService, org.mockito.Mockito.times(1)).applyAnswers(any(), any(), any());
        verify(adaptiveDiagSessionMapper, org.mockito.Mockito.times(1)).update(any(), any());
        verify(adaptiveDiagSessionMapper, never()).updateById(any(AdaptiveDiagSession.class));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // answer 用例 4：探针外题目拒绝（部分/外来/空/重复）→ BAD_REQUEST，零写入
    // ══════════════════════════════════════════════════════════════════════════

    private void stubInProgressProbe() {
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(10L, NodeState.UNKNOWN)),
                0,
                new AdaptiveDiagState.Probe(10L, List.of(1L, 2L)),
                List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));
    }

    @Test
    @DisplayName("answer 混入外来题 → BAD_REQUEST，never applyAnswers/insert/updateById")
    void answer_foreignQuestion_rejected() {
        stubInProgressProbe();
        assertThatThrownBy(() -> impl().answer(SESSION_ID, req(STUDENT_ID, a(1L, "A"), a(99L, "A"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
        verify(masteryService, never()).applyAnswers(any(), any(), any());
        verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
        verify(adaptiveDiagSessionMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("answer 部分集合(缺题) → BAD_REQUEST")
    void answer_partialSet_rejected() {
        stubInProgressProbe();
        assertThatThrownBy(() -> impl().answer(SESSION_ID, req(STUDENT_ID, a(1L, "A"))))
                .isInstanceOf(BusinessException.class);
        verify(masteryService, never()).applyAnswers(any(), any(), any());
    }

    @Test
    @DisplayName("answer 空列表 → BAD_REQUEST")
    void answer_empty_rejected() {
        stubInProgressProbe();
        assertThatThrownBy(() -> impl().answer(SESSION_ID, req(STUDENT_ID)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("answer 重复 questionId → BAD_REQUEST")
    void answer_duplicateQuestionId_rejected() {
        stubInProgressProbe();
        assertThatThrownBy(() -> impl().answer(SESSION_ID, req(STUDENT_ID, a(1L, "A"), a(1L, "A"))))
                .isInstanceOf(BusinessException.class);
        verify(masteryService, never()).applyAnswers(any(), any(), any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // answer 用例 5：幂等重放（answers == 上一探针集合）→ 返回当前探针，零写入
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("answer 幂等重放：answers == 上一探针 → 返回当前探针、never applyAnswers/insert/updateById")
    void answer_idempotentReplay_returnsCurrentProbeNoWrites() {
        // 当前探针在节点 20（题 3、4）；上一探针历史为节点 10（题 1、2）
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(10L, NodeState.TESTED_PASS, 20L, NodeState.UNKNOWN)),
                2,
                new AdaptiveDiagState.Probe(20L, List.of(3L, 4L)),
                List.of(new AdaptiveDiagState.ProbeRecord(10L, List.of(1L, 2L), true)));
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));
        // 重建当前探针 VO 需要读题 3、4
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(q(3, QuestionType.SINGLE), q(4, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(opt(3, "A", 1), opt(4, "A", 1)));

        // 重放上一探针 [1,2]
        AdaptiveDiagnosticVO vo = impl().answer(SESSION_ID, req(STUDENT_ID, a(1L, "A"), a(2L, "A")));

        assertThat(vo.isDone()).isFalse();
        assertThat(vo.getProbe().getNodeId()).isEqualTo(20L); // 当前探针
        assertThat(vo.getProbe().getQuestions()).hasSize(2);
        assertThat(vo.getProgress().getTested()).isEqualTo(1);
        // 零写入、引擎不动
        verify(masteryService, never()).applyAnswers(any(), any(), any());
        verify(answerRecordMapper, never()).insert(any(AnswerRecord.class));
        verify(adaptiveDiagSessionMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("answer 幂等重放(已完成会话)：status=1 且 answers==上一探针 → done 终局响应，零写入")
    void answer_idempotentReplay_completedSession_returnsDone() {
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(10L, NodeState.TESTED_PASS, 20L, NodeState.INFERRED_PASS)),
                2,
                null, // 已终止 currentProbe=null
                List.of(new AdaptiveDiagState.ProbeRecord(10L, List.of(1L, 2L), true)));
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(1, state));

        AdaptiveDiagnosticVO vo = impl().answer(SESSION_ID, req(STUDENT_ID, a(1L, "A"), a(2L, "A")));

        assertThat(vo.isDone()).isTrue();
        assertThat(vo.getProbe()).isNull();
        assertThat(vo.getSummary()).isNotNull();
        assertThat(vo.getSummary().getTested()).isEqualTo(1);
        assertThat(vo.getResultUrl()).contains("courseId=");
        verify(masteryService, never()).applyAnswers(any(), any(), any());
        verify(adaptiveDiagSessionMapper, never()).update(any(), any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // answer 用例 6：终止（预算耗尽）→ done + status=1
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("answer 预算耗尽：记账后 budgetUsed≥budget 且仍有 UNKNOWN → done、status=1")
    void answer_budgetExhausted_terminates() {
        // budgetUsed=14，探针 2 题 → 记账后 16 ≥ 15；节点 20 仍 UNKNOWN → BUDGET_EXHAUSTED
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(10L, NodeState.UNKNOWN, 20L, NodeState.UNKNOWN)),
                14,
                new AdaptiveDiagState.Probe(10L, List.of(101L, 102L)),
                List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(q(101, QuestionType.SINGLE), q(102, QuestionType.SINGLE)))
                .thenReturn(List.of(q(101, QuestionType.SINGLE), q(102, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(opt(101, "A", 1), opt(102, "A", 1)));
        when(questionNodeMapper.selectList(any()))
                .thenReturn(List.of(link(101, 10, 1), link(102, 10, 1)));
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(10), node(20)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());

        AdaptiveDiagnosticVO vo = impl().answer(SESSION_ID, req(STUDENT_ID, a(101L, "A"), a(102L, "A")));

        assertThat(vo.isDone()).isTrue();
        assertThat(vo.getProbe()).isNull();
        assertThat(vo.getProgress().getBudgetLeft()).isEqualTo(0); // 夹紧不出现负数（16 用 > 15 预算）
        ArgumentCaptor<AdaptiveDiagSession> sc = ArgumentCaptor.forClass(AdaptiveDiagSession.class);
        verify(adaptiveDiagSessionMapper).update(sc.capture(), any());
        assertThat(sc.getValue().getStatus()).isEqualTo(1);
        assertThat(sc.getValue().getFinishedAt()).isNotNull();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // answer 用例 7：属主/状态防线
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("answer 他人 session → FORBIDDEN(403)")
    void answer_otherStudent_forbidden() {
        stubInProgressProbe();
        assertThatThrownBy(() -> impl().answer(SESSION_ID, req(999L, a(1L, "A"), a(2L, "A"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        verify(masteryService, never()).applyAnswers(any(), any(), any());
    }

    @Test
    @DisplayName("answer session 不存在 → NOT_FOUND(404)")
    void answer_sessionNotFound_notFound() {
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(null);
        assertThatThrownBy(() -> impl().answer(SESSION_ID, req(STUDENT_ID, a(1L, "A"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
    }

    @Test
    @DisplayName("answer 已放弃(status=2) session → BAD_REQUEST(400)")
    void answer_abandonedSession_badRequest() {
        String state = stateJson(new java.util.TreeMap<>(Map.of(10L, NodeState.UNKNOWN)), 0, null, List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(2, state));
        assertThatThrownBy(() -> impl().answer(SESSION_ID, req(STUDENT_ID, a(1L, "A"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
        verify(masteryService, never()).applyAnswers(any(), any(), any());
    }

    @Test
    @DisplayName("answer 已完成(status=1) 且非上一探针 → BAD_REQUEST(400)")
    void answer_completedSession_nonMatching_badRequest() {
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(10L, NodeState.TESTED_PASS)),
                2, null,
                List.of(new AdaptiveDiagState.ProbeRecord(10L, List.of(1L, 2L), true)));
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(1, state));
        assertThatThrownBy(() -> impl().answer(SESSION_ID, req(STUDENT_ID, a(7L, "A"), a(8L, "A"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // answer 用例 10：1 题探针（1 对=PASS / 1 错=FAIL）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("answer 1 题探针：1 题对=PASS，预算记账 +1")
    void answer_singleQuestionProbe_correctIsPass() {
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(1L, NodeState.UNKNOWN, 2L, NodeState.UNKNOWN, 3L, NodeState.UNKNOWN)),
                0,
                new AdaptiveDiagState.Probe(2L, List.of(21L)),
                List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(q(21, QuestionType.SINGLE)))
                .thenReturn(List.of(q(21, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any())).thenReturn(List.of(opt(21, "A", 1)));
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(link(21, 2, 1)));
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1), node(2), node(3)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 2), prereq(2, 3)));

        AdaptiveDiagnosticVO vo = impl().answer(SESSION_ID, req(STUDENT_ID, a(21L, "A")));

        assertThat(vo.getProgress().getBudgetLeft()).isEqualTo(14); // 15 - 1
        ArgumentCaptor<AdaptiveDiagSession> sc = ArgumentCaptor.forClass(AdaptiveDiagSession.class);
        verify(adaptiveDiagSessionMapper).update(sc.capture(), any());
        AdaptiveDiagState after = parse(sc.getValue());
        assertThat(after.nodeStates().get(2L)).isEqualTo(NodeState.TESTED_PASS);
        assertThat(after.budgetUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("answer 1 题探针：1 题错=FAIL")
    void answer_singleQuestionProbe_wrongIsFail() {
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(1L, NodeState.UNKNOWN, 2L, NodeState.UNKNOWN, 3L, NodeState.UNKNOWN)),
                0,
                new AdaptiveDiagState.Probe(2L, List.of(21L)),
                List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(q(21, QuestionType.SINGLE)))
                .thenReturn(List.of(q(21, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any())).thenReturn(List.of(opt(21, "A", 1)));
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(link(21, 2, 1)));
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1), node(2), node(3)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 2), prereq(2, 3)));

        impl().answer(SESSION_ID, req(STUDENT_ID, a(21L, "B"))); // 错

        ArgumentCaptor<AdaptiveDiagSession> sc = ArgumentCaptor.forClass(AdaptiveDiagSession.class);
        verify(adaptiveDiagSessionMapper).update(sc.capture(), any());
        AdaptiveDiagState after = parse(sc.getValue());
        assertThat(after.nodeStates().get(2L)).isEqualTo(NodeState.TESTED_FAIL);
        assertThat(after.nodeStates().get(3L)).isEqualTo(NodeState.INFERRED_FAIL);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // abandon 用例 8：三态语义
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("abandon status=0 → 置 2 + finished_at + currentProbe 清空")
    void abandon_inProgress_marksAbandoned() {
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(10L, NodeState.TESTED_PASS, 20L, NodeState.UNKNOWN)),
                2,
                new AdaptiveDiagState.Probe(20L, List.of(3L, 4L)),
                List.of(new AdaptiveDiagState.ProbeRecord(10L, List.of(1L, 2L), true)));
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));

        impl().abandon(SESSION_ID, STUDENT_ID);

        // T5 硬项：abandon 改为条件更新（eq id + eq status=0），不再 updateById
        ArgumentCaptor<AdaptiveDiagSession> sc = ArgumentCaptor.forClass(AdaptiveDiagSession.class);
        verify(adaptiveDiagSessionMapper).update(sc.capture(), any());
        verify(adaptiveDiagSessionMapper, never()).updateById(any(AdaptiveDiagSession.class));
        assertThat(sc.getValue().getStatus()).isEqualTo(2);
        assertThat(sc.getValue().getFinishedAt()).isNotNull();
        AdaptiveDiagState after = parse(sc.getValue());
        assertThat(after.currentProbe()).isNull(); // 在途探针清空
        assertThat(after.nodeStates().get(10L)).isEqualTo(NodeState.TESTED_PASS); // 已实测保留
        assertThat(after.probeHistory()).hasSize(1);
    }

    @Test
    @DisplayName("abandon status=2 → 幂等成功(no-op)，不再 updateById")
    void abandon_alreadyAbandoned_idempotent() {
        String state = stateJson(new java.util.TreeMap<>(Map.of(10L, NodeState.TESTED_PASS)), 2, null, List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(2, state));

        impl().abandon(SESSION_ID, STUDENT_ID); // 不抛异常

        verify(adaptiveDiagSessionMapper, never()).updateById(any(AdaptiveDiagSession.class));
    }

    @Test
    @DisplayName("abandon status=1(已完成) → BAD_REQUEST(400)")
    void abandon_completed_badRequest() {
        String state = stateJson(new java.util.TreeMap<>(Map.of(10L, NodeState.TESTED_PASS)), 2, null, List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(1, state));

        assertThatThrownBy(() -> impl().abandon(SESSION_ID, STUDENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    @DisplayName("abandon 他人 session → FORBIDDEN(403)")
    void abandon_otherStudent_forbidden() {
        String state = stateJson(new java.util.TreeMap<>(Map.of(10L, NodeState.UNKNOWN)), 0, null, List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));
        assertThatThrownBy(() -> impl().abandon(SESSION_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
    }

    @Test
    @DisplayName("abandon session 不存在 → NOT_FOUND(404)")
    void abandon_notFound() {
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(null);
        assertThatThrownBy(() -> impl().abandon(SESSION_ID, STUDENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T5 清单 1 / 2 / 3：推断保守初值落库
    // ══════════════════════════════════════════════════════════════════════════

    /** 复用：链 1→2→3，node2 可实测，触发 finalizeSession 的基础 stub（answer 全对）。 */
    private void stubPassTermination() {
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(1L, NodeState.UNKNOWN, 2L, NodeState.UNKNOWN, 3L, NodeState.UNKNOWN)),
                0,
                new AdaptiveDiagState.Probe(2L, List.of(21L, 22L)),
                List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(q(21, QuestionType.SINGLE), q(22, QuestionType.SINGLE)))
                .thenReturn(List.of(q(21, QuestionType.SINGLE), q(22, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(opt(21, "A", 1), opt(22, "A", 1)));
        when(questionNodeMapper.selectList(any()))
                .thenReturn(List.of(link(21, 2, 1), link(22, 2, 1)));
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1), node(2), node(3)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 2), prereq(2, 3)));
    }

    /** 复用：链 1→2→3，node2 可实测，触发 finalizeSession 的基础 stub（answer 有错）。 */
    private void stubFailTermination() {
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(1L, NodeState.UNKNOWN, 2L, NodeState.UNKNOWN, 3L, NodeState.UNKNOWN)),
                0,
                new AdaptiveDiagState.Probe(2L, List.of(21L, 22L)),
                List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(q(21, QuestionType.SINGLE), q(22, QuestionType.SINGLE)))
                .thenReturn(List.of(q(21, QuestionType.SINGLE), q(22, QuestionType.SINGLE)));
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(opt(21, "A", 1), opt(22, "A", 1)));
        when(questionNodeMapper.selectList(any()))
                .thenReturn(List.of(link(21, 2, 1), link(22, 2, 1)));
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1), node(2), node(3)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 2), prereq(2, 3)));
    }

    @Test
    @DisplayName("T5 清单1a：INFERRED_PASS 无既有行 → insert score=70 + level=levelOf(70)")
    void t5_inferredPass_noExistingRow_insertsScore70() {
        stubPassTermination();
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of());
        when(masteryService.levelOf(70.0)).thenReturn(1); // 70 >= weakThreshold=40 但 < masteredThreshold=75

        impl().answer(SESSION_ID, req(STUDENT_ID, a(21L, "A"), a(22L, "A")));

        // 仅 node1 是 INFERRED_PASS → 1 次 insert
        ArgumentCaptor<StudentMastery> cap = ArgumentCaptor.forClass(StudentMastery.class);
        verify(studentMasteryMapper, times(1)).insert(cap.capture());
        StudentMastery inserted = cap.getValue();
        assertThat(inserted.getNodeId()).isEqualTo(1L);
        assertThat(inserted.getStudentId()).isEqualTo(STUDENT_ID);
        assertThat(inserted.getCourseId()).isEqualTo(COURSE_ID);
        assertThat(inserted.getMasteryScore()).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThat(inserted.getMasteryLevel()).isEqualTo(1);
    }

    @Test
    @DisplayName("T5 清单1b：INFERRED_FAIL 无既有行 → insert score=35 + level=levelOf(35)")
    void t5_inferredFail_noExistingRow_insertsScore35() {
        stubFailTermination();
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of());
        when(masteryService.levelOf(35.0)).thenReturn(0); // 35 < weakThreshold=40

        impl().answer(SESSION_ID, req(STUDENT_ID, a(21L, "A"), a(22L, "B"))); // q22 答错

        // 仅 node3 是 INFERRED_FAIL → 1 次 insert
        ArgumentCaptor<StudentMastery> cap = ArgumentCaptor.forClass(StudentMastery.class);
        verify(studentMasteryMapper, times(1)).insert(cap.capture());
        StudentMastery inserted = cap.getValue();
        assertThat(inserted.getNodeId()).isEqualTo(3L);
        assertThat(inserted.getMasteryScore()).isEqualByComparingTo(new BigDecimal("35.00"));
        assertThat(inserted.getMasteryLevel()).isEqualTo(0);
    }

    @Test
    @DisplayName("T5 清单2：已有 student_mastery 行的推断节点 → 零写入（never insert / never updateById）")
    void t5_inferredPass_existingRow_neverInserted() {
        stubPassTermination();
        // node1 已有掌握度行（来自之前练习）
        StudentMastery existing = new StudentMastery();
        existing.setId(1L);
        existing.setStudentId(STUDENT_ID);
        existing.setCourseId(COURSE_ID);
        existing.setNodeId(1L);
        existing.setMasteryScore(new BigDecimal("82.00"));
        existing.setMasteryLevel(2);
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(existing));

        impl().answer(SESSION_ID, req(STUDENT_ID, a(21L, "A"), a(22L, "A")));

        // 不得 insert（已有行不覆盖）；不得调 appendSnapshot 给 node1
        verify(studentMasteryMapper, never()).insert(any(StudentMastery.class));
        verify(masteryService, never()).appendSnapshot(any(), any(), eq(1L), any(), anyInt());
    }

    @Test
    @DisplayName("T5 清单3：TESTED 节点与 UNKNOWN 节点不在推断落库范围")
    void t5_testedAndUnknownNodes_notInserted() {
        // node2=TESTED_PASS（applyAnswers 已处理）；node3=UNKNOWN；只有 node1=INFERRED_PASS 被写
        stubPassTermination();
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of());
        when(masteryService.levelOf(anyDouble())).thenReturn(1);

        impl().answer(SESSION_ID, req(STUDENT_ID, a(21L, "A"), a(22L, "A")));

        // 仅 1 次 insert（node1 INFERRED_PASS），TESTED node2 和 UNKNOWN node3 不被 insert
        ArgumentCaptor<StudentMastery> cap = ArgumentCaptor.forClass(StudentMastery.class);
        verify(studentMasteryMapper, times(1)).insert(cap.capture());
        assertThat(cap.getValue().getNodeId()).isEqualTo(1L);
        // TESTED 节点不经 studentMasteryMapper.insert（已由 applyAnswers 处理）
        assertThat(cap.getAllValues().stream().map(StudentMastery::getNodeId))
                .doesNotContain(2L, 3L);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T5 清单 4：快照追加
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T5 清单4：推断节点新写入时 appendSnapshot 恰好被调用（内容含 nodeId/score/level）")
    void t5_inferredNode_appendsSnapshot() {
        stubPassTermination();
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of());
        when(masteryService.levelOf(70.0)).thenReturn(1);

        impl().answer(SESSION_ID, req(STUDENT_ID, a(21L, "A"), a(22L, "A")));

        // appendSnapshot 恰 1 次，node1，score=70.00，level=1
        verify(masteryService, times(1)).appendSnapshot(
                eq(STUDENT_ID), eq(COURSE_ID), eq(1L),
                eq(new BigDecimal("70.00")), eq(1));
    }

    @Test
    @DisplayName("T5 清单4：已有行的推断节点 → appendSnapshot 不被调用（不重复写快照）")
    void t5_inferredNode_existingRow_snapshotNotAppended() {
        stubPassTermination();
        StudentMastery existing = new StudentMastery();
        existing.setId(1L);
        existing.setStudentId(STUDENT_ID);
        existing.setCourseId(COURSE_ID);
        existing.setNodeId(1L);
        existing.setMasteryScore(new BigDecimal("82.00"));
        existing.setMasteryLevel(2);
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(existing));

        impl().answer(SESSION_ID, req(STUDENT_ID, a(21L, "A"), a(22L, "A")));

        verify(masteryService, never()).appendSnapshot(any(), any(), eq(1L), any(), anyInt());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T5 清单 5：SummaryVO.nodes
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T5 清单5：SummaryVO.nodes 含全节点、nodeId 升序、nodeCode/name/state 正确、kgNodeMapper 只调一次（防 N+1）")
    void t5_summaryNodes_allNodesAscending() {
        // chain 1→2→3；node2 tested pass → node1=INFERRED_PASS, node3=UNKNOWN
        stubPassTermination();
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of());
        when(masteryService.levelOf(anyDouble())).thenReturn(1);

        AdaptiveDiagnosticVO vo = impl().answer(SESSION_ID, req(STUDENT_ID, a(21L, "A"), a(22L, "A")));

        List<AdaptiveDiagnosticVO.NodeDetailVO> nodes = vo.getSummary().getNodes();
        assertThat(nodes).hasSize(3);
        // nodeId 升序
        assertThat(nodes.get(0).getNodeId()).isEqualTo(1L);
        assertThat(nodes.get(1).getNodeId()).isEqualTo(2L);
        assertThat(nodes.get(2).getNodeId()).isEqualTo(3L);
        // nodeCode 和 name 来自 fixture（node() 方法）
        assertThat(nodes.get(0).getNodeCode()).isEqualTo("KT1");
        assertThat(nodes.get(0).getName()).isEqualTo("节点1");
        assertThat(nodes.get(1).getNodeCode()).isEqualTo("KT2");
        assertThat(nodes.get(2).getName()).isEqualTo("节点3");
        // state 正确
        assertThat(nodes.get(0).getState()).isEqualTo("INFERRED_PASS");
        assertThat(nodes.get(1).getState()).isEqualTo("TESTED_PASS");
        assertThat(nodes.get(2).getState()).isEqualTo("UNKNOWN");
        // kgNodeMapper.selectList 仅一次（processAnswer 中 loadCourseNodes），finalizeSession 复用传入 nodes，防 N+1
        verify(kgNodeMapper, times(1)).selectList(any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T5 清单 6：start 即终止边界（无推断 no-op，nodes 含全 UNKNOWN 节点）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T5 清单6：start 即终止（testable 空）→ no-op 不炸、nodes=全 UNKNOWN 节点、studentMasteryMapper 无写入")
    void t5_startTerminates_noopNodesCorrect() {
        // 复用 start_terminatesImmediately 场景（questionNodeMapper 返空 → 无 testable）
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(10), node(20)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        when(questionNodeMapper.selectList(any())).thenReturn(List.of());
        stubInsertWithId(SESSION_ID);

        AdaptiveDiagnosticVO vo = impl().start(STUDENT_ID, COURSE_ID);

        assertThat(vo.isDone()).isTrue();
        // 无推断节点 → studentMasteryMapper 无批量查、无 insert、无 appendSnapshot
        verify(studentMasteryMapper, never()).selectList(any());
        verify(studentMasteryMapper, never()).insert(any(StudentMastery.class));
        verify(masteryService, never()).appendSnapshot(any(), any(), any(), any(), anyInt());
        // nodes 含 2 个 UNKNOWN 节点（全图都是 UNKNOWN）
        List<AdaptiveDiagnosticVO.NodeDetailVO> nodes = vo.getSummary().getNodes();
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).getNodeId()).isEqualTo(10L);
        assertThat(nodes.get(1).getNodeId()).isEqualTo(20L);
        assertThat(nodes).allMatch(nd -> "UNKNOWN".equals(nd.getState()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T5 清单 7：abandon 条件更新竞态
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T5 清单7a：abandon 条件 update 返回 0 + 锁定读重查 status=2 → 幂等成功（并发另一 abandon 赢了）")
    void t5_abandon_concurrentAbandonWins_idempotent() {
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(10L, NodeState.UNKNOWN)), 0,
                new AdaptiveDiagState.Probe(10L, List.of(1L, 2L)),
                List.of());
        // 入口 selectById 返回 status=0；条件 update 返回 0（并发 abandon 先一步）；
        // 重查走锁定读 selectOne(FOR UPDATE)——RR 下普通 selectById 会回旧快照，故必须当前读
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));
        when(adaptiveDiagSessionMapper.update(any(), any())).thenReturn(0);
        when(adaptiveDiagSessionMapper.selectOne(any())).thenReturn(session(2, state));

        // 不应抛异常（幂等成功）
        impl().abandon(SESSION_ID, STUDENT_ID);

        // 入口快照读恰一次 + 锁定读重查恰一次（不再第二次 selectById——RR 下它会回旧值）
        verify(adaptiveDiagSessionMapper, times(1)).selectById(SESSION_ID);
        verify(adaptiveDiagSessionMapper, times(1)).selectOne(any());
        verify(adaptiveDiagSessionMapper, never()).updateById(any(AdaptiveDiagSession.class));
    }

    @Test
    @DisplayName("T5 清单7b：abandon 条件 update 返回 0 + 锁定读重查 status=1 → BAD_REQUEST(400)（并发 answer 赢了并完成）")
    void t5_abandon_concurrentAnswerCompletes_badRequest() {
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(10L, NodeState.UNKNOWN)), 0,
                new AdaptiveDiagState.Probe(10L, List.of(1L, 2L)),
                List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));
        when(adaptiveDiagSessionMapper.update(any(), any())).thenReturn(0);
        when(adaptiveDiagSessionMapper.selectOne(any())).thenReturn(session(1, state)); // 已由 answer 完成

        assertThatThrownBy(() -> impl().abandon(SESSION_ID, STUDENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
        verify(adaptiveDiagSessionMapper, times(1)).selectById(SESSION_ID);
        verify(adaptiveDiagSessionMapper, times(1)).selectOne(any());
        verify(adaptiveDiagSessionMapper, never()).updateById(any(AdaptiveDiagSession.class));
    }
    // ══════════════════════════════════════════════════════════════════════════
    // M2 验收1整改：探针题数按剩余预算截断（budget cap），题量硬顶≤15
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 验收1 整改 RED→GREEN 测试1：budgetUsed=12，答完 2 题探针后 remaining=1，
     * 下一探针应仅 1 题（min(2, 15-14)=1）。
     * <p>证明 buildProbe 走 Math.min(PROBE_QUESTION_COUNT, maxQuestions) 分支。
     */
    @Test
    @DisplayName("budgetCap: 剩余预算=1 时下一探针仅含 1 题（min(2,1)=1）")
    void budgetCap_remaining1_nextProbeHasOneQuestion() {
        // 两孤立可实测节点 10、20；budgetUsed=12（等效已用完 6 轮 2 题探针）；
        // 当前探针在 node10，答完后 budgetUsed=14，remaining=15-14=1 → 下探针 1 题
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(10L, NodeState.UNKNOWN, 20L, NodeState.UNKNOWN)),
                12,
                new AdaptiveDiagState.Probe(10L, List.of(101L, 102L)),
                List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));

        when(questionMapper.selectList(any()))
                .thenReturn(List.of(q(101, QuestionType.SINGLE), q(102, QuestionType.SINGLE))) // 判分
                .thenReturn(List.of(q(101, QuestionType.SINGLE), q(102, QuestionType.SINGLE),
                        q(201, QuestionType.SINGLE), q(202, QuestionType.SINGLE)))              // testable
                .thenReturn(List.of(q(201, QuestionType.SINGLE), q(202, QuestionType.SINGLE))); // picker node20
        when(questionNodeMapper.selectList(any()))
                .thenReturn(List.of(link(101, 10, 1), link(102, 10, 1),
                        link(201, 20, 1), link(202, 20, 1))) // testable
                .thenReturn(List.of(link(201, 20, 1), link(202, 20, 1))); // picker node20 w1
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(opt(101, "A", 1), opt(102, "A", 1)))  // 判分正确键
                .thenReturn(List.of(opt(201, "A", 1)));                    // sanitize node20(仅 q201)
        when(answerRecordMapper.selectList(any())).thenReturn(List.of()); // 无近期
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(10), node(20)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());

        AdaptiveDiagnosticVO vo = impl().answer(SESSION_ID, req(STUDENT_ID, a(101L, "A"), a(102L, "A")));

        // 未终止，下一探针仅 1 题（剩余预算 15-14=1）
        assertThat(vo.isDone()).isFalse();
        assertThat(vo.getProbe()).isNotNull();
        assertThat(vo.getProbe().getNodeId()).isEqualTo(20L);
        assertThat(vo.getProbe().getQuestions()).hasSize(1);
        assertThat(vo.getProbe().getQuestions().get(0).getQuestionId()).isEqualTo(201L);
        assertThat(vo.getProgress().getBudgetLeft()).isEqualTo(1); // 15 - 14 = 1

        // 状态落盘：budgetUsed=14，当前探针 node20 只有 1 题
        ArgumentCaptor<AdaptiveDiagSession> sc = ArgumentCaptor.forClass(AdaptiveDiagSession.class);
        verify(adaptiveDiagSessionMapper).update(sc.capture(), any());
        AdaptiveDiagState after = parse(sc.getValue());
        assertThat(after.budgetUsed()).isEqualTo(14);
        assertThat(after.currentProbe().nodeId()).isEqualTo(20L);
        assertThat(after.currentProbe().questionIds()).containsExactly(201L);
    }

    /**
     * 验收1 整改 RED→GREEN 测试2：预算截断后 1 题探针作答，budgetUsed 精确到达 budget=15，
     * 不超预算（旧行为 budgetUsed=16 > 15）。
     */
    @Test
    @DisplayName("budgetCap: 1 题探针(budgetUsed=14)作答后 budgetUsed=15 精确终止，不超预算")
    void budgetCap_oneQuestionFinalProbe_budgetUsedEquals15NotExceeds() {
        // budgetUsed=14（由上一测试的整改状态延续），当前探针仅 1 题（budget-cap 后）；
        // 作答后 budgetUsed=14+1=15=budget → 引擎 BUDGET_EXHAUSTED → done
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(10L, NodeState.TESTED_PASS, 20L, NodeState.UNKNOWN)),
                14,
                new AdaptiveDiagState.Probe(20L, List.of(201L)),
                List.of(new AdaptiveDiagState.ProbeRecord(10L, List.of(101L, 102L), true)));
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));

        when(questionMapper.selectList(any()))
                .thenReturn(List.of(q(201, QuestionType.SINGLE)))  // 判分
                .thenReturn(List.of(q(201, QuestionType.SINGLE))); // testable
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(opt(201, "A", 1)));            // 判分正确键
        when(questionNodeMapper.selectList(any()))
                .thenReturn(List.of(link(201, 20, 1)));            // testable
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(10), node(20)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());

        AdaptiveDiagnosticVO vo = impl().answer(SESSION_ID, req(STUDENT_ID, a(201L, "A")));

        // 终止 done，预算精确满 15（budgetUsed=14+1=15 ≤ budget=15）
        assertThat(vo.isDone()).isTrue();
        assertThat(vo.getProbe()).isNull();
        assertThat(vo.getProgress().getBudgetLeft()).isEqualTo(0); // 15 - 15 = 0

        ArgumentCaptor<AdaptiveDiagSession> sc = ArgumentCaptor.forClass(AdaptiveDiagSession.class);
        verify(adaptiveDiagSessionMapper).update(sc.capture(), any());
        assertThat(sc.getValue().getStatus()).isEqualTo(1);
        // 关键断言：budgetUsed 精确=15，不超 budget（旧行为 budgetUsed=16 时此断言失败）
        AdaptiveDiagState after = parse(sc.getValue());
        assertThat(after.budgetUsed()).isEqualTo(15);
    }

    /**
     * 验收1 整改 回归测试：剩余预算≥2 时，探针仍正常出 2 题（min(2, remaining≥2)=2，无回归）。
     * 与现有 answer_notTerminated_returnsNextProbe 互为对称，显式证明正常路径未受影响。
     */
    @Test
    @DisplayName("budgetCap 回归: remaining≥2(budgetUsed=0→记账后2)时下一探针仍出 2 题，无回归")
    void budgetCap_remainingGe2_nextProbeStillHasTwoQuestions() {
        // 同 answer_notTerminated_returnsNextProbe 场景：budgetUsed=0，答完 2 题后 remaining=13
        String state = stateJson(
                new java.util.TreeMap<>(Map.of(10L, NodeState.UNKNOWN, 20L, NodeState.UNKNOWN)),
                0,
                new AdaptiveDiagState.Probe(10L, List.of(101L, 102L)),
                List.of());
        when(adaptiveDiagSessionMapper.selectById(SESSION_ID)).thenReturn(session(0, state));

        when(questionMapper.selectList(any()))
                .thenReturn(List.of(q(101, QuestionType.SINGLE), q(102, QuestionType.SINGLE))) // 判分
                .thenReturn(List.of(q(101, QuestionType.SINGLE), q(102, QuestionType.SINGLE),
                        q(201, QuestionType.SINGLE), q(202, QuestionType.SINGLE)))              // testable
                .thenReturn(List.of(q(201, QuestionType.SINGLE), q(202, QuestionType.SINGLE))); // picker node20
        when(questionNodeMapper.selectList(any()))
                .thenReturn(List.of(link(101, 10, 1), link(102, 10, 1),
                        link(201, 20, 1), link(202, 20, 1))) // testable
                .thenReturn(List.of(link(201, 20, 1), link(202, 20, 1))); // picker node20 w1
        when(questionOptionMapper.selectList(any()))
                .thenReturn(List.of(opt(101, "A", 1), opt(102, "A", 1)))
                .thenReturn(List.of(opt(201, "A", 1), opt(202, "A", 1)));
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(10), node(20)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());

        AdaptiveDiagnosticVO vo = impl().answer(SESSION_ID, req(STUDENT_ID, a(101L, "A"), a(102L, "A")));

        // 剩余预算=13，探针应有 min(2,13)=2 题（无截断）
        assertThat(vo.isDone()).isFalse();
        assertThat(vo.getProbe()).isNotNull();
        assertThat(vo.getProbe().getNodeId()).isEqualTo(20L);
        assertThat(vo.getProbe().getQuestions()).hasSize(2);  // 关键：仍为 2 题，无回归
        assertThat(vo.getProgress().getBudgetLeft()).isEqualTo(13); // 15 - 2 = 13
    }

}
