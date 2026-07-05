package com.wenjin.service;

import com.wenjin.dto.EvidenceVO;
import com.wenjin.entity.AdaptiveDiagSession;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.AdaptiveDiagSessionMapper;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.impl.AdaptiveDiagnosticServiceImpl;
import com.wenjin.support.AdaptiveDiagState;
import com.wenjin.support.NodeState;
import com.wenjin.support.PrereqDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AdaptiveDiagnosticService.latestEvidence 单元测试（M2 T8 TDD）。
 *
 * <p>覆盖简报测试清单：
 * <ol>
 *   <li>3 节点链 A→B→C：探 B 答对 → A=INFERRED_PASS,fromNode=B,fromPass=true；
 *       探 B 答错 → C=INFERRED_FAIL,fromNode=B,fromPass=false。</li>
 *   <li>回放终态==存储 nodeStates（含矛盾序列）。</li>
 *   <li>无会话/只有 status=0或2 → null。</li>
 *   <li>多个已完成会话 → 取最新（finished_at 降序）。</li>
 *   <li>计数与终态一致；inferredNodes 升序且只含 INFERRED_*。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdaptiveDiagnosticEvidenceServiceTest {

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
    private static final Long COURSE_ID = 5L;
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

    // ── fixture helpers ───────────────────────────────────────────────────────

    /** 课程节点（id, name）。 */
    private KgNode node(long id, String name) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setCourseId(COURSE_ID);
        n.setNodeCode("KT" + id);
        n.setName(name);
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

    /** 已完成会话（status=1）。 */
    private AdaptiveDiagSession completedSession(long id, LocalDateTime finishedAt, String stateJson) {
        AdaptiveDiagSession s = new AdaptiveDiagSession();
        s.setId(id);
        s.setStudentId(STUDENT_ID);
        s.setCourseId(COURSE_ID);
        s.setStatus(1); // SESSION_DONE
        s.setFinishedAt(finishedAt);
        s.setStateJson(stateJson);
        return s;
    }

    /**
     * 构造 state_json：给定终态 nodeStates + probeHistory，返回已完成会话的 state_json。
     * currentProbe=null（已完成会话无在途探针）。
     */
    private String makeStateJson(Map<Long, NodeState> nodeStates,
                                  List<AdaptiveDiagState.ProbeRecord> history) {
        int budgetUsed = history.stream().mapToInt(r -> r.questionIds().size()).sum();
        return new AdaptiveDiagState(nodeStates, budgetUsed, null, history).toJson();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 测试 1：3 节点链 A(1)→B(2)→C(3)，探 B
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("测试 1：3 节点链 A→B→C 归因")
    class ThreeNodeChain {

        /** 链式图节点（A=1, B=2, C=3）和边（A→B, B→C）。 */
        private void stubGraph() {
            when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                    node(1L, "节点A"), node(2L, "节点B"), node(3L, "节点C")));
            when(kgEdgeMapper.selectList(any())).thenReturn(List.of(
                    prereq(1L, 2L), prereq(2L, 3L)));
        }

        @Test
        @DisplayName("探 B 答对 → A=INFERRED_PASS, fromNode=B, fromPass=true")
        void probeB_pass_causesA_inferredPass() {
            // B 答对 → A（B 的前置祖先）= INFERRED_PASS
            // C（B 的后继）不受影响（B pass 传播方向是前置，不是后继）
            Map<Long, NodeState> storedStates = new TreeMap<>(Map.of(
                    1L, NodeState.INFERRED_PASS,
                    2L, NodeState.TESTED_PASS,
                    3L, NodeState.UNKNOWN));
            List<AdaptiveDiagState.ProbeRecord> history = List.of(
                    new AdaptiveDiagState.ProbeRecord(2L, List.of(101L, 102L), true));

            String stateJson = makeStateJson(storedStates, history);
            stubGraph();
            when(adaptiveDiagSessionMapper.selectOne(any()))
                    .thenReturn(completedSession(500L, LocalDateTime.now(), stateJson));

            EvidenceVO ev = impl().latestEvidence(STUDENT_ID, COURSE_ID);

            assertThat(ev).isNotNull();
            assertThat(ev.getSessionId()).isEqualTo(500L);
            assertThat(ev.getTested()).isEqualTo(1);
            assertThat(ev.getInferredPass()).isEqualTo(1);
            assertThat(ev.getInferredFail()).isEqualTo(0);
            assertThat(ev.getUnknown()).isEqualTo(1);

            assertThat(ev.getInferredNodes()).hasSize(1);
            EvidenceVO.InferredNodeVO a = ev.getInferredNodes().get(0);
            assertThat(a.getNodeId()).isEqualTo(1L);
            assertThat(a.getName()).isEqualTo("节点A");
            assertThat(a.getState()).isEqualTo("INFERRED_PASS");
            assertThat(a.getFromNodeId()).isEqualTo(2L);
            assertThat(a.getFromName()).isEqualTo("节点B");
            assertThat(a.isFromPass()).isTrue();
        }

        @Test
        @DisplayName("探 B 答错 → C=INFERRED_FAIL, fromNode=B, fromPass=false")
        void probeB_fail_causesC_inferredFail() {
            // B 答错 → C（B 的后继子孙）= INFERRED_FAIL；A（B 的前置）不受影响
            Map<Long, NodeState> storedStates = new TreeMap<>(Map.of(
                    1L, NodeState.UNKNOWN,
                    2L, NodeState.TESTED_FAIL,
                    3L, NodeState.INFERRED_FAIL));
            List<AdaptiveDiagState.ProbeRecord> history = List.of(
                    new AdaptiveDiagState.ProbeRecord(2L, List.of(101L, 102L), false));

            String stateJson = makeStateJson(storedStates, history);
            stubGraph();
            when(adaptiveDiagSessionMapper.selectOne(any()))
                    .thenReturn(completedSession(501L, LocalDateTime.now(), stateJson));

            EvidenceVO ev = impl().latestEvidence(STUDENT_ID, COURSE_ID);

            assertThat(ev.getInferredNodes()).hasSize(1);
            EvidenceVO.InferredNodeVO c = ev.getInferredNodes().get(0);
            assertThat(c.getNodeId()).isEqualTo(3L);
            assertThat(c.getName()).isEqualTo("节点C");
            assertThat(c.getState()).isEqualTo("INFERRED_FAIL");
            assertThat(c.getFromNodeId()).isEqualTo(2L);
            assertThat(c.getFromName()).isEqualTo("节点B");
            assertThat(c.isFromPass()).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 测试 2：回放终态 == 存储 nodeStates（含矛盾序列）
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("测试 2：回放终态==存储终态（性质测试）")
    class ReplayFinalStateProperty {

        @Test
        @DisplayName("单次探针回放终态==存储终态")
        void singleProbe_replayMatchesStored() {
            // A(1)→B(2)→C(3)，探 B pass：最终 A=INFERRED_PASS, B=TESTED_PASS, C=UNKNOWN
            Map<Long, NodeState> storedStates = new TreeMap<>(Map.of(
                    1L, NodeState.INFERRED_PASS,
                    2L, NodeState.TESTED_PASS,
                    3L, NodeState.UNKNOWN));
            List<AdaptiveDiagState.ProbeRecord> history = List.of(
                    new AdaptiveDiagState.ProbeRecord(2L, List.of(101L), true));
            String stateJson = makeStateJson(storedStates, history);

            when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                    node(1L, "A"), node(2L, "B"), node(3L, "C")));
            when(kgEdgeMapper.selectList(any())).thenReturn(List.of(
                    prereq(1L, 2L), prereq(2L, 3L)));
            when(adaptiveDiagSessionMapper.selectOne(any()))
                    .thenReturn(completedSession(600L, LocalDateTime.now(), stateJson));

            // latestEvidence 内部做回放断言；若回放终态与存储终态不一致会抛 IllegalStateException
            // 这里只要不抛异常就说明回放正确
            EvidenceVO ev = impl().latestEvidence(STUDENT_ID, COURSE_ID);
            assertThat(ev).isNotNull();
        }

        @Test
        @DisplayName("矛盾作答序列（B pass 后 A fail）回放终态==存储终态")
        void contradictoryProbes_replayMatchesStored() {
            // A(1)→B(2)→C(3)
            // 探 B pass：A=INFERRED_PASS, B=TESTED_PASS, C=UNKNOWN
            // 探 A fail：A=TESTED_FAIL（覆盖 INFERRED_PASS）；
            //             propagate to dependents of A={B}：B=TESTED_PASS → isTested → 不变
            //             BFS continues from B: dependents of B={C}→ applyInference(C, INFERRED_FAIL)
            //             C was UNKNOWN → C=INFERRED_FAIL
            // Final: A=TESTED_FAIL, B=TESTED_PASS, C=INFERRED_FAIL（注意B已实测，不被推断覆盖）
            //
            // Wait: 实际上 dependentsOf[A]={B}，B 是 A 的 dependent；BFS 从 A 出发访问 B。
            // applyInference(B, INFERRED_FAIL)：B=TESTED_PASS → isTested → return（不变）。
            // BFS 继续：访问 B 的邻居 dependentsOf[B]={C}→ applyInference(C, INFERRED_FAIL)
            //             C=UNKNOWN → C=INFERRED_FAIL。
            // 所以 Final: A=TESTED_FAIL, B=TESTED_PASS, C=INFERRED_FAIL
            Map<Long, NodeState> storedStates = new TreeMap<>(Map.of(
                    1L, NodeState.TESTED_FAIL,
                    2L, NodeState.TESTED_PASS,
                    3L, NodeState.INFERRED_FAIL));
            List<AdaptiveDiagState.ProbeRecord> history = List.of(
                    new AdaptiveDiagState.ProbeRecord(2L, List.of(101L), true),
                    new AdaptiveDiagState.ProbeRecord(1L, List.of(201L), false));
            String stateJson = makeStateJson(storedStates, history);

            when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                    node(1L, "A"), node(2L, "B"), node(3L, "C")));
            when(kgEdgeMapper.selectList(any())).thenReturn(List.of(
                    prereq(1L, 2L), prereq(2L, 3L)));
            when(adaptiveDiagSessionMapper.selectOne(any()))
                    .thenReturn(completedSession(601L, LocalDateTime.now(), stateJson));

            EvidenceVO ev = impl().latestEvidence(STUDENT_ID, COURSE_ID);
            assertThat(ev).isNotNull();
            // A=TESTED_FAIL, B=TESTED_PASS → tested=2; C=INFERRED_FAIL → inferredFail=1
            assertThat(ev.getTested()).isEqualTo(2);
            assertThat(ev.getInferredFail()).isEqualTo(1);
            assertThat(ev.getInferredPass()).isEqualTo(0);
            // C 的成因是 A 答错（最后一次使 C 进入 INFERRED_FAIL 的是探针 A fail）
            assertThat(ev.getInferredNodes()).hasSize(1);
            EvidenceVO.InferredNodeVO c = ev.getInferredNodes().get(0);
            assertThat(c.getNodeId()).isEqualTo(3L);
            assertThat(c.getFromNodeId()).isEqualTo(1L); // 成因=A fail
            assertThat(c.isFromPass()).isFalse();
        }

        @Test
        @DisplayName("不一致存储终态 → 抛 IllegalStateException（生产代码宁可响亮失败）")
        void inconsistentStoredState_throwsIllegalState() {
            // 探 B pass 后 A 应为 INFERRED_PASS，但存储故意写错成 INFERRED_FAIL
            Map<Long, NodeState> wrongStates = new TreeMap<>(Map.of(
                    1L, NodeState.INFERRED_FAIL, // wrong! should be INFERRED_PASS
                    2L, NodeState.TESTED_PASS,
                    3L, NodeState.UNKNOWN));
            List<AdaptiveDiagState.ProbeRecord> history = List.of(
                    new AdaptiveDiagState.ProbeRecord(2L, List.of(101L), true));
            // use wrong states in stored json
            String stateJson = new AdaptiveDiagState(wrongStates, 1, null, history).toJson();

            when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                    node(1L, "A"), node(2L, "B"), node(3L, "C")));
            when(kgEdgeMapper.selectList(any())).thenReturn(List.of(
                    prereq(1L, 2L), prereq(2L, 3L)));
            when(adaptiveDiagSessionMapper.selectOne(any()))
                    .thenReturn(completedSession(602L, LocalDateTime.now(), stateJson));

            assertThatThrownBy(() -> impl().latestEvidence(STUDENT_ID, COURSE_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("回放终态与存储终态不一致");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 测试 3：无会话/只有 status=0或2 → null
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无任何会话 → null")
    void noSession_returnsNull() {
        when(adaptiveDiagSessionMapper.selectOne(any())).thenReturn(null);
        assertThat(impl().latestEvidence(STUDENT_ID, COURSE_ID)).isNull();
    }

    @Test
    @DisplayName("只有进行中会话（status=0）→ null（查询条件过滤，mapper 返回 null）")
    void onlyInProgressSession_returnsNull() {
        // 查询 WHERE status=1 的会话，mapper 返回 null（表中只有 status=0 的会话）
        when(adaptiveDiagSessionMapper.selectOne(any())).thenReturn(null);
        assertThat(impl().latestEvidence(STUDENT_ID, COURSE_ID)).isNull();
    }

    @Test
    @DisplayName("只有放弃会话（status=2）→ null（查询条件过滤，mapper 返回 null）")
    void onlyAbandonedSession_returnsNull() {
        when(adaptiveDiagSessionMapper.selectOne(any())).thenReturn(null);
        assertThat(impl().latestEvidence(STUDENT_ID, COURSE_ID)).isNull();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 测试 4：多个已完成会话 → 取最新（finished_at 降序）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("多个已完成会话 → selectOne 返回的是 mapper 按最新排序取到的那个（sessionId=700）")
    void multipleCompletedSessions_latestIsReturned() {
        // 单节点，无边，一次探针
        Map<Long, NodeState> storedStates = new TreeMap<>(Map.of(1L, NodeState.TESTED_PASS));
        List<AdaptiveDiagState.ProbeRecord> history = List.of(
                new AdaptiveDiagState.ProbeRecord(1L, List.of(101L), true));
        String stateJson = makeStateJson(storedStates, history);

        // 模拟 mapper 已按 finished_at DESC 排序取最新的 session（id=700）
        LocalDateTime latest = LocalDateTime.of(2026, 7, 5, 12, 0);
        when(adaptiveDiagSessionMapper.selectOne(any()))
                .thenReturn(completedSession(700L, latest, stateJson));
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1L, "节点1")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());

        EvidenceVO ev = impl().latestEvidence(STUDENT_ID, COURSE_ID);

        assertThat(ev.getSessionId()).isEqualTo(700L);
        assertThat(ev.getFinishedAt()).isEqualTo(latest);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 测试 5：计数一致 + inferredNodes 升序 + 只含 INFERRED_*
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("计数与终态一致；inferredNodes 升序且只含 INFERRED_*")
    void countsAndOrdering_correct() {
        // 5 节点图：A(1)→B(2)→C(3)→D(4), 无边 E(5)
        // 探 B(2) pass：A=INFERRED_PASS（B 的前置祖先）
        // 探 C(3) fail：D=INFERRED_FAIL（C 的后继子孙）
        // Final: A=INFERRED_PASS, B=TESTED_PASS, C=TESTED_FAIL, D=INFERRED_FAIL, E=UNKNOWN
        Map<Long, NodeState> storedStates = new TreeMap<>(Map.of(
                1L, NodeState.INFERRED_PASS,
                2L, NodeState.TESTED_PASS,
                3L, NodeState.TESTED_FAIL,
                4L, NodeState.INFERRED_FAIL,
                5L, NodeState.UNKNOWN));
        List<AdaptiveDiagState.ProbeRecord> history = List.of(
                new AdaptiveDiagState.ProbeRecord(2L, List.of(101L), true),
                new AdaptiveDiagState.ProbeRecord(3L, List.of(201L), false));
        String stateJson = makeStateJson(storedStates, history);

        when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                node(1L, "A"), node(2L, "B"), node(3L, "C"), node(4L, "D"), node(5L, "E")));
        // A→B→C→D chain（prereq: from 是 to 的前置）
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(
                prereq(1L, 2L), prereq(2L, 3L), prereq(3L, 4L)));
        when(adaptiveDiagSessionMapper.selectOne(any()))
                .thenReturn(completedSession(800L, LocalDateTime.now(), stateJson));

        EvidenceVO ev = impl().latestEvidence(STUDENT_ID, COURSE_ID);

        // 计数：tested=2(B,C), inferredPass=1(A), inferredFail=1(D), unknown=1(E)
        assertThat(ev.getTested()).isEqualTo(2);
        assertThat(ev.getInferredPass()).isEqualTo(1);
        assertThat(ev.getInferredFail()).isEqualTo(1);
        assertThat(ev.getUnknown()).isEqualTo(1);

        // inferredNodes 只含 INFERRED_*：A(1)=INFERRED_PASS, D(4)=INFERRED_FAIL
        assertThat(ev.getInferredNodes()).hasSize(2);
        // 按 nodeId 升序：A(1) 在前，D(4) 在后
        assertThat(ev.getInferredNodes().get(0).getNodeId()).isEqualTo(1L);
        assertThat(ev.getInferredNodes().get(0).getState()).isEqualTo("INFERRED_PASS");
        assertThat(ev.getInferredNodes().get(1).getNodeId()).isEqualTo(4L);
        assertThat(ev.getInferredNodes().get(1).getState()).isEqualTo("INFERRED_FAIL");

        // A 的成因：探 B pass（B答对推断A通过）
        assertThat(ev.getInferredNodes().get(0).getFromNodeId()).isEqualTo(2L);
        assertThat(ev.getInferredNodes().get(0).isFromPass()).isTrue();

        // D 的成因：探 C fail（C答错推断D失败）
        assertThat(ev.getInferredNodes().get(1).getFromNodeId()).isEqualTo(3L);
        assertThat(ev.getInferredNodes().get(1).isFromPass()).isFalse();

        // E 不在 inferredNodes（E=UNKNOWN）
        assertThat(ev.getInferredNodes()).noneMatch(n -> n.getNodeId().equals(5L));
        // B, C 也不在 inferredNodes（TESTED_*）
        assertThat(ev.getInferredNodes()).noneMatch(n -> n.getNodeId().equals(2L));
        assertThat(ev.getInferredNodes()).noneMatch(n -> n.getNodeId().equals(3L));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 测试 6：空会话（无探针历史）→ 全部 UNKNOWN，inferredNodes 空
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("probeHistory 为空（所有节点 UNKNOWN）→ inferredNodes 为空列表")
    void emptyHistory_allUnknown_emptyInferredNodes() {
        Map<Long, NodeState> storedStates = new TreeMap<>(Map.of(
                1L, NodeState.UNKNOWN,
                2L, NodeState.UNKNOWN));
        String stateJson = makeStateJson(storedStates, List.of());

        when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                node(1L, "A"), node(2L, "B")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1L, 2L)));
        when(adaptiveDiagSessionMapper.selectOne(any()))
                .thenReturn(completedSession(900L, LocalDateTime.now(), stateJson));

        EvidenceVO ev = impl().latestEvidence(STUDENT_ID, COURSE_ID);

        assertThat(ev.getTested()).isEqualTo(0);
        assertThat(ev.getInferredPass()).isEqualTo(0);
        assertThat(ev.getInferredFail()).isEqualTo(0);
        assertThat(ev.getUnknown()).isEqualTo(2);
        assertThat(ev.getInferredNodes()).isEmpty();
    }
}
