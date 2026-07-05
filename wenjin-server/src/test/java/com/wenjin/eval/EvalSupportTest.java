package com.wenjin.eval;

import com.wenjin.entity.KgEdge;
import com.wenjin.support.NodeState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * {@link EvalSupport} 纯函数单测（enabled，进全量）。
 *
 * <p>覆盖 4 组：① Kahn 分层后半切分（含并列与孤立节点）
 *              ② 着色一致率（含无行=未学、分母=总节点）
 *              ③ 二分类映射与准确率（含对称口径新增）
 *              ④ markdown 渲染 golden 断言
 */
class EvalSupportTest {

    // ─────────────────────────────────────────────────────────────────────────
    // 辅助：创建前置边
    // ─────────────────────────────────────────────────────────────────────────

    /** 创建 relationType=1 前置边（from 是 to 的先修）。 */
    private static KgEdge prereq(long from, long to) {
        KgEdge e = new KgEdge();
        e.setFromNodeId(from);
        e.setToNodeId(to);
        e.setRelationType(1);
        return e;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ① Kahn 分层后半切分
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("① kahnBackHalf：分层后半切分")
    class KahnBackHalfTests {

        @Test
        @DisplayName("线性链：1→2→3；弱集 = 后半 = {2,3}")
        void linearChain() {
            List<Long> nodes = List.of(1L, 2L, 3L);
            List<KgEdge> edges = List.of(prereq(1, 2), prereq(2, 3));
            // layers: 1=0, 2=1, 3=2
            // sorted: [1, 2, 3]; n=3, backStart=1 → weak={2,3}
            Set<Long> weak = EvalSupport.kahnBackHalf(nodes, edges);
            assertThat(weak).containsExactlyInAnyOrder(2L, 3L);
            assertThat(weak).doesNotContain(1L);
        }

        @Test
        @DisplayName("全孤立节点（无边）：nodeId 升序切分，后 ceil(n/2) 为弱集")
        void allIsolated() {
            List<Long> nodes = List.of(1L, 2L, 3L, 4L);
            // 无边：全部层 0，按 nodeId 排序 [1,2,3,4]，后 2 个为弱
            Set<Long> weak = EvalSupport.kahnBackHalf(nodes, List.of());
            assertThat(weak).hasSize(2);
            assertThat(weak).containsExactlyInAnyOrder(3L, 4L);
        }

        @Test
        @DisplayName("奇数节点孤立：n=3 全层0，后 ceil(3/2)=2 个为弱")
        void oddIsolated() {
            List<Long> nodes = List.of(10L, 20L, 30L);
            Set<Long> weak = EvalSupport.kahnBackHalf(nodes, List.of());
            // sorted: [10,20,30]; backStart=1; weak={20,30}
            assertThat(weak).containsExactlyInAnyOrder(20L, 30L);
        }

        @Test
        @DisplayName("孤立节点与有向边混合：并列截断按 nodeId 稳定切分")
        void mixedIsolatedAndConnected() {
            // 节点 1,2,3,4；边 1→2（1 是 2 的前置）；3,4 孤立
            List<Long> nodes = List.of(1L, 2L, 3L, 4L);
            List<KgEdge> edges = List.of(prereq(1, 2));
            // layers: 1=0, 3=0, 4=0, 2=1
            // sorted by (layer, nodeId): [1, 3, 4, 2]
            // n=4, backStart=2 → weak = {4, 2}
            Set<Long> weak = EvalSupport.kahnBackHalf(nodes, edges);
            assertThat(weak).containsExactlyInAnyOrder(2L, 4L);
            // 节点 1（层0，位0）和 3（层0，位1）应在强集中
            assertThat(weak).doesNotContain(1L, 3L);
        }

        @Test
        @DisplayName("空节点集返回空集")
        void emptyNodes() {
            assertThat(EvalSupport.kahnBackHalf(List.of(), List.of())).isEmpty();
        }

        @Test
        @DisplayName("DAG 菱形：公共前置不被重复计层")
        void diamond() {
            // 1→3, 2→3, 3→4
            // layers: 1=0, 2=0, 3=1, 4=2
            List<Long> nodes = List.of(1L, 2L, 3L, 4L);
            List<KgEdge> edges = List.of(prereq(1, 3), prereq(2, 3), prereq(3, 4));
            // sorted: [1,2,3,4]; backStart=2 → weak={3,4}
            Set<Long> weak = EvalSupport.kahnBackHalf(nodes, edges);
            assertThat(weak).containsExactlyInAnyOrder(3L, 4L);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ② 着色一致率
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("② 着色一致率：无行=未学=level0，分母=总节点数")
    class AgreementRateTests {

        @Test
        @DisplayName("全部同色：一致率 = 1.0")
        void allSame() {
            Map<Long, Integer> a = Map.of(1L, 2, 2L, 1, 3L, 0);
            Map<Long, Integer> f = Map.of(1L, 2, 2L, 1, 3L, 0);
            double rate = EvalSupport.computeAgreementRate(a, f, Set.of(1L, 2L, 3L));
            assertThat(rate).isEqualTo(1.0);
        }

        @Test
        @DisplayName("全不同色：一致率 = 0.0")
        void allDifferent() {
            Map<Long, Integer> a = Map.of(1L, 2, 2L, 1);
            Map<Long, Integer> f = Map.of(1L, 0, 2L, 2);
            double rate = EvalSupport.computeAgreementRate(a, f, Set.of(1L, 2L));
            assertThat(rate).isEqualTo(0.0);
        }

        @Test
        @DisplayName("无行节点视为 level=0 参与比较")
        void missingNodeAsUnlearned() {
            // 节点 3 在两臂均无 mastery 行 → 双方 level=0 → 同色
            Map<Long, Integer> a = Map.of(1L, 2);
            Map<Long, Integer> f = Map.of(1L, 2);
            // 节点 1 同色，节点 3 双方均 0 同色
            double rate = EvalSupport.computeAgreementRate(a, f, Set.of(1L, 3L));
            assertThat(rate).isEqualTo(1.0);
        }

        @Test
        @DisplayName("一臂无行视为 0，与另一臂 level=1 不同色")
        void oneSideUnlearned() {
            // 节点 2：adaptive=level1，fixed 无行=level0 → 不同色
            // 节点 1：双方 level2 → 同色
            Map<Long, Integer> a = Map.of(1L, 2, 2L, 1);
            Map<Long, Integer> f = Map.of(1L, 2);
            double rate = EvalSupport.computeAgreementRate(a, f, Set.of(1L, 2L));
            assertThat(rate).isEqualTo(0.5, offset(1e-9));
        }

        @Test
        @DisplayName("交集一致率：仅在双方有行的节点上计算")
        void intersectionRate() {
            // node1: a=2, f=2 → same; node2: a=1, f无行 → 不入交集; node3: a=2, f=0 → different
            Map<Long, Integer> a = Map.of(1L, 2, 2L, 1, 3L, 2);
            Map<Long, Integer> f = Map.of(1L, 2, 3L, 0);
            // 交集 = {1, 3}; node1 same, node3 diff → 1/2 = 0.5
            double rate = EvalSupport.computeIntersectionAgreementRate(a, f);
            assertThat(rate).isEqualTo(0.5, offset(1e-9));
        }

        @Test
        @DisplayName("交集为空时返回 0")
        void emptyIntersection() {
            Map<Long, Integer> a = Map.of(1L, 2);
            Map<Long, Integer> f = Map.of(2L, 1);
            assertThat(EvalSupport.computeIntersectionAgreementRate(a, f)).isEqualTo(0.0);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ③ 二分类映射与准确率（含对称口径）
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("③ 二分类映射与准确率（含对称口径）")
    class BinaryClassifyTests {

        @Test
        @DisplayName("binaryClassifyByState：各状态映射正确")
        void stateMapping() {
            assertThat(EvalSupport.binaryClassifyByState(NodeState.TESTED_PASS)).isTrue();
            assertThat(EvalSupport.binaryClassifyByState(NodeState.INFERRED_PASS)).isTrue();
            assertThat(EvalSupport.binaryClassifyByState(NodeState.TESTED_FAIL)).isFalse();
            assertThat(EvalSupport.binaryClassifyByState(NodeState.INFERRED_FAIL)).isFalse();
            assertThat(EvalSupport.binaryClassifyByState(NodeState.UNKNOWN)).isNull();
            assertThat(EvalSupport.binaryClassifyByState(null)).isNull();
        }

        @Test
        @DisplayName("binaryClassifyByScore：score=50 为 PASS 边界")
        void scoreMapping() {
            assertThat(EvalSupport.binaryClassifyByScore(65.0)).isTrue();   // 冷启动答对
            assertThat(EvalSupport.binaryClassifyByScore(50.0)).isTrue();   // 边界
            assertThat(EvalSupport.binaryClassifyByScore(49.9)).isFalse();  // 边界下
            assertThat(EvalSupport.binaryClassifyByScore(30.0)).isFalse();  // 冷启动答错
            assertThat(EvalSupport.binaryClassifyByScore(0.0)).isFalse();
            assertThat(EvalSupport.binaryClassifyByScore(100.0)).isTrue();
        }

        @Test
        @DisplayName("computeAccuracy：UNKNOWN 排除，准确率计算正确")
        void accuracy() {
            // weakNodes = {2, 3}（ground truth FAIL）
            // node1: pred=true(PASS), gt=PASS → correct
            // node2: pred=false(FAIL), gt=FAIL → correct
            // node3: pred=true(PASS), gt=FAIL → wrong
            // node4: pred=null(UNKNOWN) → excluded
            Set<Long> weakNodes = Set.of(2L, 3L);
            Set<Long> allNodes = Set.of(1L, 2L, 3L, 4L);
            Map<Long, Boolean> preds = Map.of(1L, true, 2L, false, 3L, true); // node4 absent = null
            double acc = EvalSupport.computeAccuracy(preds, weakNodes, allNodes);
            // correct=2, total=3 → 2/3
            assertThat(acc).isEqualTo(2.0 / 3.0, offset(1e-9));
        }

        @Test
        @DisplayName("computeAccuracy：全正确 → 1.0")
        void perfectAccuracy() {
            Set<Long> weakNodes = Set.of(2L);
            Set<Long> allNodes = Set.of(1L, 2L);
            Map<Long, Boolean> preds = Map.of(1L, true, 2L, false);
            assertThat(EvalSupport.computeAccuracy(preds, weakNodes, allNodes)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("computeAccuracy：无有效预测（全 UNKNOWN）→ 0.0")
        void noValidPredictions() {
            Set<Long> weakNodes = Set.of(1L);
            Set<Long> allNodes = Set.of(1L);
            Map<Long, Boolean> preds = Map.of(); // null (absent) = excluded
            assertThat(EvalSupport.computeAccuracy(preds, weakNodes, allNodes)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("固定覆盖内：无 mastery 行节点 → 缺席(null) → 排除出分母")
        void fixedCoveredExcludesNoRowNodes() {
            // weakNodes = {2, 3}; node3 has no mastery row → absent from map → null → excluded
            // node1: has row, score≥50 → true(PASS), gt=PASS → correct
            // node2: has row, score<50 → false(FAIL), gt=FAIL → correct
            // node3: no row → absent → excluded from denominator
            Set<Long> weakNodes = Set.of(2L, 3L);
            Set<Long> allNodes = Set.of(1L, 2L, 3L);
            Map<Long, Boolean> fixedPredsCovered = Map.of(1L, true, 2L, false);
            double acc = EvalSupport.computeAccuracy(fixedPredsCovered, weakNodes, allNodes);
            // correct=2, total=2 → 1.0
            assertThat(acc).isEqualTo(1.0, offset(1e-9));
        }

        @Test
        @DisplayName("自适应全图：UNKNOWN 节点计为 FAIL → 纳入分母")
        void adaptiveAllImputesUnknownAsFail() {
            // weakNodes = {3}; node2 was UNKNOWN → imputed as false(FAIL)
            // node1: true(PASS), gt=PASS → correct
            // node2: false(FAIL imputed), gt=PASS(not weak) → wrong
            // node3: false(FAIL), gt=FAIL(weak) → correct
            Set<Long> weakNodes = Set.of(3L);
            Set<Long> allNodes = Set.of(1L, 2L, 3L);
            Map<Long, Boolean> adaptivePredsAll = Map.of(1L, true, 2L, false, 3L, false);
            double acc = EvalSupport.computeAccuracy(adaptivePredsAll, weakNodes, allNodes);
            // correct=2, total=3 → 2/3
            assertThat(acc).isEqualTo(2.0 / 3.0, offset(1e-9));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ④ Markdown 渲染 golden 断言
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("④ markdown 渲染 golden 断言")
    class MarkdownRenderTests {

        @Test
        @DisplayName("renderMarkdownTable：表头四准确率列 + 数据行格式正确；固定卷题数取传入值")
        void golden() {
            // fixedQuestionCount=23（非 25）验证不再硬编码
            EvalSupport.ProfileResult r = new EvalSupport.ProfileResult(
                    "全弱", 15, 23, 30, 18, 39, 9,
                    0.80, 0.90, 0.75, 0.68, 0.62, 0.65);
            String table = EvalSupport.renderMarkdownTable(List.of(r));

            // 表头：新四列
            assertThat(table).contains("| 画像 | 自适应题数 | 固定卷题数");
            assertThat(table).contains("自适应准确率(覆盖内)");
            assertThat(table).contains("自适应准确率(全图)");
            assertThat(table).contains("固定准确率(覆盖内)");
            assertThat(table).contains("固定准确率(全图)");
            assertThat(table).contains("|------|");

            // 数据行：数值正确
            assertThat(table).contains("| 全弱 |");
            assertThat(table).contains("| 15 |");
            assertThat(table).contains("| 23 |");   // 传入值，非硬编码 25
            assertThat(table).contains("30/39");
            assertThat(table).contains("18/39");
            assertThat(table).contains("| 9 |");
            assertThat(table).contains("80.0%");
            assertThat(table).contains("90.0%");
            assertThat(table).contains("75.0%");    // adaptiveAccuracyCovered
            assertThat(table).contains("68.0%");    // adaptiveAccuracyAll
            assertThat(table).contains("62.0%");    // fixedAccuracyCovered
            assertThat(table).contains("65.0%");    // fixedAccuracyAll
        }

        @Test
        @DisplayName("conclusionLine：包含核心数值（四准确率 + 覆盖 + 一致率）")
        void conclusionLineContent() {
            EvalSupport.ProfileResult r = new EvalSupport.ProfileResult(
                    "半弱", 12, 25, 39, 20, 39, 0,
                    0.85, 0.92, 0.80, 0.75, 0.70, 0.72);
            String line = EvalSupport.conclusionLine(r);

            assertThat(line).contains("半弱");
            assertThat(line).contains("12 题");
            assertThat(line).contains("39/39");
            assertThat(line).contains("UNKNOWN=0");
            assertThat(line).contains("25 题");
            assertThat(line).contains("85.0%");    // agreementRate
            assertThat(line).contains("80.0%");    // adaptiveAccuracyCovered
            assertThat(line).contains("75.0%");    // adaptiveAccuracyAll
            assertThat(line).contains("70.0%");    // fixedAccuracyCovered
            assertThat(line).contains("72.0%");    // fixedAccuracyAll
        }

        @Test
        @DisplayName("多画像表：行数正确")
        void multipleProfiles() {
            EvalSupport.ProfileResult r1 = new EvalSupport.ProfileResult(
                    "全弱", 15, 25, 35, 20, 39, 4, 0.82, 0.88, 0.78, 0.72, 0.68, 0.70);
            EvalSupport.ProfileResult r2 = new EvalSupport.ProfileResult(
                    "半弱", 12, 25, 39, 22, 39, 0, 0.87, 0.93, 0.85, 0.80, 0.76, 0.76);
            EvalSupport.ProfileResult r3 = new EvalSupport.ProfileResult(
                    "全强", 8, 25, 38, 24, 39, 1, 0.90, 0.95, 0.88, 0.85, 0.82, 0.82);

            String table = EvalSupport.renderMarkdownTable(List.of(r1, r2, r3));
            String[] lines = table.split("\\R");
            // 1 表头行 + 1 分隔行 + 3 数据行 = 5
            assertThat(lines).hasSize(5);
            assertThat(table).contains("全弱");
            assertThat(table).contains("半弱");
            assertThat(table).contains("全强");
        }
    }
}
