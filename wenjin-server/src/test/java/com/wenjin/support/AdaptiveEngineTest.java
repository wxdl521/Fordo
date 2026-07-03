package com.wenjin.support;

import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * AdaptiveEngine 纯算法核心测试（M2 T2 TDD）。
 *
 * <p>覆盖任务简报 9 项测试清单：
 * <ol>
 *   <li>链式图传播（答对推祖先 / 答错推子孙）；</li>
 *   <li>菱形图冲突回退 UNKNOWN + 探针顺序无关一例；</li>
 *   <li>预算终止；</li>
 *   <li>空图 / 单节点边界；</li>
 *   <li>无可实测节点终止；</li>
 *   <li>gain 手算选点 + 平手裁决链（isKey 优先 / 度数 / nodeId 兜底）；</li>
 *   <li>推断态复测 gain×0.5 + 实测覆盖推断继续传播；</li>
 *   <li>状态恢复重建行为一致；</li>
 *   <li>性质测试（随机 DAG，固定种子）。</li>
 * </ol>
 *
 * <p><b>性质测试的不变量口径说明（重要）</b>：简报第 9 项的字面不变量含
 * "不存在 TESTED_FAIL 祖先 + INFERRED_PASS 子孙"。该子句在<b>含推断态复测</b>的
 * 探针序列下可被真实作答的矛盾合法触发——反例（4 节点链 P→S→X→Y）：
 * <pre>
 *   probe(P, FAIL)  ⇒ S,X,Y = INFERRED_FAIL
 *   probe(Y, PASS)  （复测 INFERRED_FAIL 节点，裁决 2/7 明确允许）
 *                   ⇒ X,S 反向冲突回退 UNKNOWN
 *   probe(X, PASS)  ⇒ S = INFERRED_PASS，而 P = TESTED_FAIL 仍是其祖先
 * </pre>
 * 这正是简报注记"真实作答可以矛盾，实测永不被改；不变量只约束推断状态"所豁免的
 * 情形（S 的推断锚定在真实通过的 X 上，与 P 的矛盾源于真实作答矛盾）。
 * 因此性质测试拆三条：
 * <ul>
 *   <li>A：仅探测 UNKNOWN 节点（无复测）——断言字面完整不变量；</li>
 *   <li>B：按候选集探测（含 INFERRED 复测）——断言纯推断不变量
 *       （不存在 INFERRED_FAIL 祖先 + INFERRED_PASS 子孙，可证明与顺序无关恒成立）；</li>
 *   <li>C：引擎自选驱动——必然终止（≤ 节点数步）+ 纯推断不变量。</li>
 * </ul>
 */
class AdaptiveEngineTest {

    private static final int DEFAULT_BUDGET = 15;

    // ── 构图工具 ────────────────────────────────────────────────────────────

    private static KgNode node(long id) {
        return node(id, 0);
    }

    private static KgNode node(long id, int isKey) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setIsKey(isKey);
        return n;
    }

    /** 前置边：from 是 to 的前置（T1 钉死方向，经 PrereqDirection 语义构造）。 */
    private static KgEdge prereq(long from, long to) {
        KgEdge e = new KgEdge();
        e.setFromNodeId(from);
        e.setToNodeId(to);
        e.setRelationType(PrereqDirection.RELATION_TYPE_PREREQUISITE);
        return e;
    }

    private static KgEdge edgeOfType(long from, long to, int relationType) {
        KgEdge e = new KgEdge();
        e.setFromNodeId(from);
        e.setToNodeId(to);
        e.setRelationType(relationType);
        return e;
    }

    private static List<KgNode> nodes(long... ids) {
        List<KgNode> list = new ArrayList<>();
        for (long id : ids) {
            list.add(node(id));
        }
        return list;
    }

    private static Set<Long> idsOf(List<KgNode> nodes) {
        return nodes.stream().map(KgNode::getId).collect(Collectors.toSet());
    }

    /** 全节点可实测的新会话引擎。 */
    private static AdaptiveEngine engineOf(List<KgNode> nodes, List<KgEdge> edges, int budget) {
        return new AdaptiveEngine(nodes, edges, idsOf(nodes), budget);
    }

    /** 链 1→2→3（1 是 2 的前置，2 是 3 的前置）。 */
    private static AdaptiveEngine chain3() {
        return engineOf(nodes(1, 2, 3), List.of(prereq(1, 2), prereq(2, 3)), DEFAULT_BUDGET);
    }

    /** 链 1→2→3→4。 */
    private static AdaptiveEngine chain4(int budget) {
        return engineOf(nodes(1, 2, 3, 4),
                List.of(prereq(1, 2), prereq(2, 3), prereq(3, 4)), budget);
    }

    /** 菱形 1→2, 1→3, 2→4, 3→4。 */
    private static AdaptiveEngine diamond() {
        return engineOf(nodes(1, 2, 3, 4),
                List.of(prereq(1, 2), prereq(1, 3), prereq(2, 4), prereq(3, 4)), DEFAULT_BUDGET);
    }

    // ── 1. 链式图传播 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("清单1：链式图状态机与传播")
    class ChainPropagation {

        @Test
        @DisplayName("答对 B ⇒ 祖先 A=INFERRED_PASS，后继 C 保持 UNKNOWN")
        void passOnMiddle_infersAncestorPass_keepsDescendantUnknown() {
            AdaptiveEngine engine = chain3();

            engine.recordProbeResult(2, true, 2);

            assertThat(engine.stateOf(2)).isEqualTo(NodeState.TESTED_PASS);
            assertThat(engine.stateOf(1)).isEqualTo(NodeState.INFERRED_PASS);
            assertThat(engine.stateOf(3)).isEqualTo(NodeState.UNKNOWN);
            assertThat(engine.summary())
                    .isEqualTo(new AdaptiveEngine.Summary(1, 1, 0, 1));
        }

        @Test
        @DisplayName("答错 B ⇒ 子孙 C=INFERRED_FAIL，祖先 A 保持 UNKNOWN")
        void failOnMiddle_infersDescendantFail_keepsAncestorUnknown() {
            AdaptiveEngine engine = chain3();

            engine.recordProbeResult(2, false, 2);

            assertThat(engine.stateOf(2)).isEqualTo(NodeState.TESTED_FAIL);
            assertThat(engine.stateOf(3)).isEqualTo(NodeState.INFERRED_FAIL);
            assertThat(engine.stateOf(1)).isEqualTo(NodeState.UNKNOWN);
        }

        @Test
        @DisplayName("传播沿链穿透多级：答对链尾 ⇒ 全体祖先 INFERRED_PASS")
        void passOnTail_propagatesThroughAllAncestors() {
            AdaptiveEngine engine = chain4(DEFAULT_BUDGET);

            engine.recordProbeResult(4, true, 2);

            assertThat(engine.stateOf(1)).isEqualTo(NodeState.INFERRED_PASS);
            assertThat(engine.stateOf(2)).isEqualTo(NodeState.INFERRED_PASS);
            assertThat(engine.stateOf(3)).isEqualTo(NodeState.INFERRED_PASS);
        }

        @Test
        @DisplayName("同方向推断幂等：已 INFERRED_PASS 再收 PASS 传播保持不变")
        void sameDirectionInference_isIdempotent() {
            AdaptiveEngine engine = chain3();
            engine.recordProbeResult(3, true, 2);
            assertThat(engine.stateOf(1)).isEqualTo(NodeState.INFERRED_PASS);

            // 复测 2（INFERRED_PASS）答对：1 再次收到 PASS 传播，保持 INFERRED_PASS
            engine.recordProbeResult(2, true, 2);

            assertThat(engine.stateOf(2)).isEqualTo(NodeState.TESTED_PASS);
            assertThat(engine.stateOf(1)).isEqualTo(NodeState.INFERRED_PASS);
        }

        @Test
        @DisplayName("非前置边（relation_type≠1）不参与传播（防御性过滤）")
        void nonPrereqEdges_areIgnored() {
            // 1→2 前置；2→3 是"包含"边，不得传播
            AdaptiveEngine engine = engineOf(nodes(1, 2, 3),
                    List.of(prereq(1, 2), edgeOfType(2, 3, RelationType.CONTAINS.getCode())),
                    DEFAULT_BUDGET);

            engine.recordProbeResult(3, true, 1);

            assertThat(engine.stateOf(1)).isEqualTo(NodeState.UNKNOWN);
            assertThat(engine.stateOf(2)).isEqualTo(NodeState.UNKNOWN);
        }
    }

    // ── 2. 菱形冲突 + 顺序无关 ─────────────────────────────────────────────

    @Nested
    @DisplayName("清单2：菱形图冲突回退与顺序无关")
    class DiamondConflict {

        @Test
        @DisplayName("反向传播冲突 ⇒ 推断节点回退 UNKNOWN，实测节点永不改动")
        void oppositeInference_revertsToUnknown_testedUntouched() {
            AdaptiveEngine engine = diamond();
            engine.recordProbeResult(4, true, 2);   // 1,2,3 = INFERRED_PASS
            assertThat(engine.stateOf(2)).isEqualTo(NodeState.INFERRED_PASS);
            assertThat(engine.stateOf(3)).isEqualTo(NodeState.INFERRED_PASS);

            engine.recordProbeResult(1, false, 2);  // FAIL 向子孙传播

            assertThat(engine.stateOf(1)).isEqualTo(NodeState.TESTED_FAIL);
            // 2/3 原为 INFERRED_PASS，收到反向 FAIL 推断 ⇒ 回退 UNKNOWN
            assertThat(engine.stateOf(2)).isEqualTo(NodeState.UNKNOWN);
            assertThat(engine.stateOf(3)).isEqualTo(NodeState.UNKNOWN);
            // 4 是实测，FAIL 传播不得改动
            assertThat(engine.stateOf(4)).isEqualTo(NodeState.TESTED_PASS);
        }

        @Test
        @DisplayName("两种探针顺序（先4后1 / 先1后4）终态一致")
        void probeOrder_doesNotChangeFinalStates() {
            AdaptiveEngine first = diamond();
            first.recordProbeResult(4, true, 2);
            first.recordProbeResult(1, false, 2);

            AdaptiveEngine second = diamond();
            second.recordProbeResult(1, false, 2);
            second.recordProbeResult(4, true, 2);

            assertThat(first.statesView()).isEqualTo(second.statesView());
            assertThat(first.statesView()).containsEntry(1L, NodeState.TESTED_FAIL)
                    .containsEntry(2L, NodeState.UNKNOWN)
                    .containsEntry(3L, NodeState.UNKNOWN)
                    .containsEntry(4L, NodeState.TESTED_PASS);
        }
    }

    // ── 3. 预算终止 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("清单3：预算终止")
    class BudgetTermination {

        @Test
        @DisplayName("记账到预算 ⇒ 终止，原因=预算耗尽，选点返回空")
        void budgetExhausted_terminates() {
            AdaptiveEngine engine = chain4(3);

            engine.recordProbeResult(2, true, 2);
            assertThat(engine.getBudgetUsed()).isEqualTo(2);
            assertThat(engine.isTerminated()).isFalse();
            assertThat(engine.terminationReason())
                    .isEqualTo(AdaptiveEngine.TerminationReason.NONE);

            engine.recordProbeResult(3, true, 1);
            assertThat(engine.getBudgetUsed()).isEqualTo(3);
            assertThat(engine.isTerminated()).isTrue();
            assertThat(engine.terminationReason())
                    .isEqualTo(AdaptiveEngine.TerminationReason.BUDGET_EXHAUSTED);
            assertThat(engine.selectNextNode()).isEmpty();
        }

        @Test
        @DisplayName("budget=0 ⇒ 尚有 UNKNOWN 时立即按预算耗尽终止")
        void zeroBudget_terminatesImmediately() {
            AdaptiveEngine engine = chain4(0);

            assertThat(engine.isTerminated()).isTrue();
            assertThat(engine.terminationReason())
                    .isEqualTo(AdaptiveEngine.TerminationReason.BUDGET_EXHAUSTED);
        }
    }

    // ── 4. 空图 / 单节点边界 ───────────────────────────────────────────────

    @Nested
    @DisplayName("清单4：空图与单节点边界")
    class EmptyAndSingleNode {

        @Test
        @DisplayName("空图：立即终止（无 UNKNOWN），选点为空，汇总全 0")
        void emptyGraph_terminatesImmediately() {
            AdaptiveEngine engine = new AdaptiveEngine(List.of(), List.of(), Set.of(), DEFAULT_BUDGET);

            assertThat(engine.isTerminated()).isTrue();
            assertThat(engine.terminationReason())
                    .isEqualTo(AdaptiveEngine.TerminationReason.ALL_KNOWN);
            assertThat(engine.selectNextNode()).isEmpty();
            assertThat(engine.summary()).isEqualTo(new AdaptiveEngine.Summary(0, 0, 0, 0));
        }

        @Test
        @DisplayName("单节点：可选中该节点，实测后按无 UNKNOWN 终止")
        void singleNode_selectableThenAllKnown() {
            AdaptiveEngine engine = engineOf(nodes(7), List.of(), DEFAULT_BUDGET);

            assertThat(engine.isTerminated()).isFalse();
            assertThat(engine.selectNextNode()).contains(7L);

            engine.recordProbeResult(7, false, 2);

            assertThat(engine.stateOf(7)).isEqualTo(NodeState.TESTED_FAIL);
            assertThat(engine.isTerminated()).isTrue();
            assertThat(engine.terminationReason())
                    .isEqualTo(AdaptiveEngine.TerminationReason.ALL_KNOWN);
            assertThat(engine.summary()).isEqualTo(new AdaptiveEngine.Summary(1, 0, 0, 0));
        }

        @Test
        @DisplayName("非法输入防御：未知节点/负 questionsUsed 抛 IllegalArgumentException")
        void invalidInput_throws() {
            AdaptiveEngine engine = chain3();

            assertThatThrownBy(() -> engine.recordProbeResult(999, true, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> engine.recordProbeResult(1, true, -1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> engine.stateOf(999))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── 5. 无可实测节点终止 ────────────────────────────────────────────────

    @Nested
    @DisplayName("清单5：无可实测节点终止")
    class NoTestableTermination {

        @Test
        @DisplayName("testable 为空 ⇒ 立即按无可实测终止")
        void emptyTestable_terminates() {
            AdaptiveEngine engine = new AdaptiveEngine(nodes(1, 2, 3),
                    List.of(prereq(1, 2), prereq(2, 3)), Set.of(), DEFAULT_BUDGET);

            assertThat(engine.isTerminated()).isTrue();
            assertThat(engine.terminationReason())
                    .isEqualTo(AdaptiveEngine.TerminationReason.NO_TESTABLE);
            assertThat(engine.selectNextNode()).isEmpty();
        }

        @Test
        @DisplayName("剩余候选均不可实测 ⇒ 按无可实测终止（仍有 UNKNOWN、预算未尽）")
        void remainingCandidatesNotTestable_terminates() {
            AdaptiveEngine engine = new AdaptiveEngine(nodes(1, 2, 3),
                    List.of(prereq(1, 2), prereq(2, 3)), Set.of(2L), DEFAULT_BUDGET);
            assertThat(engine.selectNextNode()).contains(2L);

            engine.recordProbeResult(2, true, 2);

            // 1=INFERRED_PASS、3=UNKNOWN，但均不可实测
            assertThat(engine.stateOf(3)).isEqualTo(NodeState.UNKNOWN);
            assertThat(engine.isTerminated()).isTrue();
            assertThat(engine.terminationReason())
                    .isEqualTo(AdaptiveEngine.TerminationReason.NO_TESTABLE);
        }
    }

    // ── 6. gain 选点与平手裁决链 ───────────────────────────────────────────

    @Nested
    @DisplayName("清单6：gain 贪心选点与平手裁决链")
    class GainSelection {

        @Test
        @DisplayName("手算 gain：星形图 1→{2,3,4}，gain(1)=3 最大被选中")
        void starGraph_hubHasMaxGain() {
            AdaptiveEngine engine = engineOf(nodes(1, 2, 3, 4),
                    List.of(prereq(1, 2), prereq(1, 3), prereq(1, 4)), DEFAULT_BUDGET);

            // gain(1)=0祖先+3子孙=3；gain(2/3/4)=1祖先+0子孙=1
            assertThat(engine.selectNextNode()).contains(1L);
        }

        @Test
        @DisplayName("gain 平手 ⇒ 度数大的枢纽胜出：链 1→2→3 选中间节点 2")
        void tieBrokenByDegree_hubWins() {
            AdaptiveEngine engine = chain3();

            // gain(1)=2, gain(2)=1+1=2, gain(3)=2；均非 key；度数 1:1, 2:2, 3:1
            assertThat(engine.selectNextNode()).contains(2L);
        }

        @Test
        @DisplayName("gain 平手 ⇒ is_key=1 优先于度数")
        void tieBrokenByIsKey_beforeDegree() {
            // 节点1→{2,3}：gain(1)=2、度数2；孤立 key 节点4：gain=0+0+2=2、度数0
            List<KgNode> ns = new ArrayList<>(nodes(1, 2, 3));
            ns.add(node(4, 1));
            AdaptiveEngine engine = new AdaptiveEngine(ns,
                    List.of(prereq(1, 2), prereq(1, 3)),
                    Set.of(1L, 2L, 3L, 4L), DEFAULT_BUDGET);

            assertThat(engine.selectNextNode()).contains(4L);
        }

        @Test
        @DisplayName("全平手 ⇒ nodeId 最小者兜底（完全确定性）")
        void fullTie_smallestNodeIdWins() {
            // 两个孤立节点 9 和 4（注意乱序传入），全指标相同
            AdaptiveEngine engine = engineOf(nodes(9, 4), List.of(), DEFAULT_BUDGET);

            assertThat(engine.selectNextNode()).contains(4L);
        }
    }

    // ── 7. 推断态复测 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("清单7：推断态复测（gain×0.5 与实测覆盖后继续传播）")
    class InferredRetest {

        @Test
        @DisplayName("INFERRED 候选 gain×0.5：原始 gain 4 的推断节点输给 gain 3 的 UNKNOWN 节点")
        void inferredCandidate_gainHalved() {
            // 图：1→{2,3,4,5}（4 个 UNKNOWN 子孙），6→{7,8,9}（3 个 UNKNOWN 子孙）
            List<KgNode> ns = nodes(1, 2, 3, 4, 5, 6, 7, 8, 9);
            List<KgEdge> es = List.of(prereq(1, 2), prereq(1, 3), prereq(1, 4), prereq(1, 5),
                    prereq(6, 7), prereq(6, 8), prereq(6, 9));

            // 无推断态时：gain(1)=4 > gain(6)=3 ⇒ 选 1
            AdaptiveEngine fresh = engineOf(ns, es, DEFAULT_BUDGET);
            assertThat(fresh.selectNextNode()).contains(1L);

            // 1 为 INFERRED_PASS 时：gain(1)=4×0.5=2 < gain(6)=3 ⇒ 选 6
            AdaptiveEngine restored = new AdaptiveEngine(ns, es, idsOf(ns), DEFAULT_BUDGET,
                    Map.of(1L, NodeState.INFERRED_PASS), 0);
            assertThat(restored.selectNextNode()).contains(6L);
        }

        @Test
        @DisplayName("复测覆盖推断并继续传播：INFERRED_PASS 节点实测 FAIL 后向子孙传播")
        void retestOverridesInference_thenPropagates() {
            AdaptiveEngine engine = chain4(DEFAULT_BUDGET);
            engine.recordProbeResult(4, true, 2);   // 1,2,3 = INFERRED_PASS
            assertThat(engine.stateOf(2)).isEqualTo(NodeState.INFERRED_PASS);

            engine.recordProbeResult(2, false, 2);  // 复测 2：实测覆盖推断

            assertThat(engine.stateOf(2)).isEqualTo(NodeState.TESTED_FAIL);
            // 3 原为 INFERRED_PASS，收到反向 FAIL ⇒ 回退 UNKNOWN
            assertThat(engine.stateOf(3)).isEqualTo(NodeState.UNKNOWN);
            // 4 实测不动；1 不在传播方向上保持 INFERRED_PASS
            assertThat(engine.stateOf(4)).isEqualTo(NodeState.TESTED_PASS);
            assertThat(engine.stateOf(1)).isEqualTo(NodeState.INFERRED_PASS);
        }
    }

    // ── 8. 状态恢复 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("清单8：从中途状态恢复重建")
    class StateRestore {

        @Test
        @DisplayName("恢复引擎与连续运行引擎：下一步选点相同、后续状态一致")
        void restoredEngine_behavesSameAsContinuous() {
            // 菱形 + 尾节点 5：探完 4 后 5 仍是 UNKNOWN，会话不会因"无 UNKNOWN"终止
            List<KgNode> ns = nodes(1, 2, 3, 4, 5);
            List<KgEdge> es = List.of(prereq(1, 2), prereq(1, 3), prereq(2, 4), prereq(3, 4),
                    prereq(4, 5));

            AdaptiveEngine continuous = engineOf(ns, es, DEFAULT_BUDGET);
            continuous.recordProbeResult(4, true, 2);

            AdaptiveEngine restored = new AdaptiveEngine(ns, es, idsOf(ns), DEFAULT_BUDGET,
                    continuous.statesView(), continuous.getBudgetUsed());

            assertThat(restored.getBudgetUsed()).isEqualTo(continuous.getBudgetUsed());
            assertThat(restored.statesView()).isEqualTo(continuous.statesView());
            Optional<Long> next = continuous.selectNextNode();
            assertThat(restored.selectNextNode()).isEqualTo(next);

            // 双方执行同一后续探针，终态仍一致
            continuous.recordProbeResult(next.orElseThrow(), false, 1);
            restored.recordProbeResult(next.orElseThrow(), false, 1);
            assertThat(restored.statesView()).isEqualTo(continuous.statesView());
            assertThat(restored.getBudgetUsed()).isEqualTo(continuous.getBudgetUsed());
            assertThat(restored.terminationReason()).isEqualTo(continuous.terminationReason());
        }

        @Test
        @DisplayName("恢复容错：状态 Map 中的未知节点 ID 被忽略（图谱漂移防御）")
        void restore_ignoresUnknownNodeIds() {
            AdaptiveEngine engine = new AdaptiveEngine(nodes(1, 2, 3),
                    List.of(prereq(1, 2), prereq(2, 3)), Set.of(1L, 2L, 3L), DEFAULT_BUDGET,
                    Map.of(1L, NodeState.INFERRED_PASS, 999L, NodeState.TESTED_FAIL), 4);

            assertThat(engine.stateOf(1)).isEqualTo(NodeState.INFERRED_PASS);
            assertThat(engine.statesView()).doesNotContainKey(999L);
            assertThat(engine.getBudgetUsed()).isEqualTo(4);
        }
    }

    // ── 9. 性质测试（随机 DAG，固定种子） ──────────────────────────────────

    @Nested
    @DisplayName("清单9：随机 DAG 性质测试（固定种子可复现）")
    class PropertyTests {

        @Test
        @DisplayName("性质A：仅探测 UNKNOWN 节点 ⇒ 全程无『前置 FAIL 而后继 PASS』矛盾（字面完整不变量）")
        void unknownOnlyProbing_fullInvariantHolds() {
            for (long seed = 1; seed <= 15; seed++) {
                Random rnd = new Random(seed);
                Dag dag = randomDag(rnd, 8 + (int) (seed % 9), 0.3);
                AdaptiveEngine engine = new AdaptiveEngine(dag.nodes, dag.edges,
                        dag.ids, Integer.MAX_VALUE);
                assertThat(engine.statesView()).hasSize(dag.ids.size());
                Map<Long, Set<Long>> ancestors = ancestorsByNode(dag.edges, dag.ids);

                for (int step = 0; step < dag.ids.size() * 5; step++) {
                    List<Long> unknowns = engine.statesView().entrySet().stream()
                            .filter(e -> e.getValue() == NodeState.UNKNOWN)
                            .map(Map.Entry::getKey)
                            .collect(Collectors.toList());
                    if (unknowns.isEmpty()) {
                        break;
                    }
                    long target = unknowns.get(rnd.nextInt(unknowns.size()));
                    engine.recordProbeResult(target, rnd.nextBoolean(), 1);

                    String violation = findInferenceViolation(engine.statesView(), ancestors, true);
                    if (violation != null) {
                        fail("性质A违约 seed=" + seed + " step=" + step + ": " + violation);
                    }
                }
            }
        }

        @Test
        @DisplayName("性质B：候选集探测（含 INFERRED 复测）⇒ 全程无纯推断矛盾（IF 祖先 + IP 子孙）")
        void candidateProbingWithRetests_inferredInvariantHolds() {
            for (long seed = 101; seed <= 115; seed++) {
                Random rnd = new Random(seed);
                Dag dag = randomDag(rnd, 8 + (int) (seed % 9), 0.3);
                AdaptiveEngine engine = new AdaptiveEngine(dag.nodes, dag.edges,
                        dag.ids, Integer.MAX_VALUE);
                assertThat(engine.statesView()).hasSize(dag.ids.size());
                Map<Long, Set<Long>> ancestors = ancestorsByNode(dag.edges, dag.ids);

                for (int step = 0; step < dag.ids.size() * 6; step++) {
                    List<Long> candidates = engine.statesView().entrySet().stream()
                            .filter(e -> !e.getValue().isTested())
                            .map(Map.Entry::getKey)
                            .collect(Collectors.toList());
                    if (candidates.isEmpty()) {
                        break;
                    }
                    long target = candidates.get(rnd.nextInt(candidates.size()));
                    engine.recordProbeResult(target, rnd.nextBoolean(), 1);

                    String violation = findInferenceViolation(engine.statesView(), ancestors, false);
                    if (violation != null) {
                        fail("性质B违约 seed=" + seed + " step=" + step + ": " + violation);
                    }
                }
            }
        }

        @Test
        @DisplayName("性质C：引擎自选驱动 ⇒ 必然在 ≤ 节点数步内终止，且无纯推断矛盾")
        void engineDrivenSession_terminatesWithinNodeCount() {
            for (long seed = 201; seed <= 212; seed++) {
                Random rnd = new Random(seed);
                int n = 8 + (int) (seed % 9);
                Dag dag = randomDag(rnd, n, 0.3);
                int budget = 10 + (int) (seed % 10);
                AdaptiveEngine engine = new AdaptiveEngine(dag.nodes, dag.edges, dag.ids, budget);
                Map<Long, Set<Long>> ancestors = ancestorsByNode(dag.edges, dag.ids);

                int steps = 0;
                while (!engine.isTerminated()) {
                    assertThat(steps)
                            .as("seed=%d 引擎自选驱动步数不应超过节点数", seed)
                            .isLessThan(n + 1);
                    Optional<Long> next = engine.selectNextNode();
                    assertThat(next).as("seed=%d 未终止时必须能选出节点", seed).isPresent();
                    engine.recordProbeResult(next.orElseThrow(), rnd.nextBoolean(), 1 + rnd.nextInt(2));
                    steps++;

                    String violation = findInferenceViolation(engine.statesView(), ancestors, false);
                    if (violation != null) {
                        fail("性质C违约 seed=" + seed + " step=" + steps + ": " + violation);
                    }
                }
                assertThat(engine.terminationReason())
                        .as("seed=%d 终止后必须给出原因", seed)
                        .isNotEqualTo(AdaptiveEngine.TerminationReason.NONE);
                assertThat(engine.selectNextNode()).isEmpty();
            }
        }
    }

    // ── 性质测试工具 ────────────────────────────────────────────────────────

    private record Dag(List<KgNode> nodes, List<KgEdge> edges, Set<Long> ids) { }

    /**
     * 随机 DAG：节点 1..n，仅允许 i&lt;j 的边 i→j（拓扑序保证无环），
     * 每条候选边以概率 p 出现，25% 节点为 is_key。
     */
    private static Dag randomDag(Random rnd, int n, double p) {
        List<KgNode> ns = new ArrayList<>();
        for (long id = 1; id <= n; id++) {
            ns.add(node(id, rnd.nextInt(4) == 0 ? 1 : 0));
        }
        List<KgEdge> es = new ArrayList<>();
        for (long i = 1; i <= n; i++) {
            for (long j = i + 1; j <= n; j++) {
                if (rnd.nextDouble() < p) {
                    es.add(prereq(i, j));
                }
            }
        }
        return new Dag(ns, es, idsOf(ns));
    }

    /** 测试侧独立计算每个节点的前置祖先闭包（经 PrereqDirection 访问器，带访问标记）。 */
    private static Map<Long, Set<Long>> ancestorsByNode(List<KgEdge> edges, Set<Long> ids) {
        Map<Long, List<Long>> prereqOf = new HashMap<>();
        for (KgEdge e : edges) {
            prereqOf.computeIfAbsent(PrereqDirection.dependentNodeId(e), k -> new ArrayList<>())
                    .add(PrereqDirection.prerequisiteNodeId(e));
        }
        Map<Long, Set<Long>> result = new HashMap<>();
        for (Long id : ids) {
            Set<Long> visited = new HashSet<>();
            Deque<Long> queue = new ArrayDeque<>();
            queue.add(id);
            while (!queue.isEmpty()) {
                Long cur = queue.poll();
                for (Long prereq : prereqOf.getOrDefault(cur, List.of())) {
                    if (visited.add(prereq)) {
                        queue.add(prereq);
                    }
                }
            }
            result.put(id, visited);
        }
        return result;
    }

    /**
     * 推断矛盾检查。返回 null 表示无违约，否则返回违约描述。
     *
     * @param strictTestedClauses true=性质A的字面完整口径（额外禁止
     *                            TESTED_FAIL 祖先+INFERRED_PASS 子孙、
     *                            INFERRED_FAIL 祖先+TESTED_PASS 子孙）；
     *                            false=纯推断口径（仅禁止 INFERRED_FAIL 祖先+INFERRED_PASS 子孙）
     */
    private static String findInferenceViolation(Map<Long, NodeState> states,
                                                 Map<Long, Set<Long>> ancestors,
                                                 boolean strictTestedClauses) {
        for (Map.Entry<Long, Set<Long>> entry : ancestors.entrySet()) {
            Long s = entry.getKey();
            NodeState ss = states.get(s);
            for (Long p : entry.getValue()) {
                NodeState ps = states.get(p);
                boolean bad = ps == NodeState.INFERRED_FAIL && ss == NodeState.INFERRED_PASS;
                if (strictTestedClauses) {
                    bad = bad
                            || (ps == NodeState.TESTED_FAIL && ss == NodeState.INFERRED_PASS)
                            || (ps == NodeState.INFERRED_FAIL && ss == NodeState.TESTED_PASS);
                }
                if (bad) {
                    return "祖先 P=" + p + "(" + ps + ") 与后继 S=" + s + "(" + ss + ") 矛盾";
                }
            }
        }
        return null;
    }
}
