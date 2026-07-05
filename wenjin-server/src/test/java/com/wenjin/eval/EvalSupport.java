package com.wenjin.eval;

import com.wenjin.entity.KgEdge;
import com.wenjin.support.NodeState;
import com.wenjin.support.PrereqDirection;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 纯静态辅助函数（自适应 vs 固定卷评估，M2 T9）。
 *
 * <p>无 Spring 依赖，可直接静态调用。配合常规单测 {@link EvalSupportTest}（enabled，进全量）。
 *
 * <h2>方法一览</h2>
 * <ul>
 *   <li>{@link #kahnBackHalf} — Kahn 分层，返回层号靠后的 50% 节点（半弱画像弱点集）</li>
 *   <li>{@link #computeAgreementRate} — 着色一致率（验收3口径）</li>
 *   <li>{@link #computeIntersectionAgreementRate} — 交集一致率（剥离覆盖差）</li>
 *   <li>{@link #binaryClassifyByState} — 自适应二分类（*_PASS=掌握/*_FAIL=薄弱/UNKNOWN=排除）</li>
 *   <li>{@link #binaryClassifyByScore} — 固定卷二分类（score≥50=掌握）</li>
 *   <li>{@link #computeAccuracy} — 二分类准确率（vs. ground truth 弱点集）</li>
 *   <li>{@link #renderMarkdownTable} — 渲染评估 markdown 表</li>
 *   <li>{@link #conclusionLine} — 单行结论（可直接贴答辩稿）</li>
 * </ul>
 */
public final class EvalSupport {

    private EvalSupport() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Kahn 分层 · 半弱节点集
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Kahn 分层，返回层号靠后的 50% 节点 ID（半弱画像的弱点节点集）。
     *
     * <p>规则：
     * <ul>
     *   <li>前置边方向：{@link PrereqDirection} 权威——from_node_id 是 to_node_id 的先修；</li>
     *   <li>孤立节点（无任何 relation_type=1 的边与之关联）算层 0；</li>
     *   <li>并列截断：全节点按 (层号 ASC, nodeId ASC) 排序后，取后 ceil(n/2) 个为弱集；</li>
     *   <li>有向环节点（Kahn 无法到达，实测图谱不含环）兜底层 0。</li>
     * </ul>
     *
     * @param nodeIds     课程全节点 ID 列表（顺序无关，但不得含重复）
     * @param prereqEdges 前置边（relationType=1）
     * @return 弱点节点 ID 集合（半弱画像用）；空图返回空集
     */
    public static Set<Long> kahnBackHalf(List<Long> nodeIds, List<KgEdge> prereqEdges) {
        if (nodeIds == null || nodeIds.isEmpty()) return Set.of();

        Set<Long> nodeSet = new HashSet<>(nodeIds);

        // adj[prereq] = 依赖 prereq 的节点列表（prereq 是它们的前置）
        Map<Long, List<Long>> adj = new HashMap<>();
        Map<Long, Integer> inDegree = new HashMap<>();
        for (Long id : nodeIds) {
            adj.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        }

        for (KgEdge edge : prereqEdges) {
            Long prereq = PrereqDirection.prerequisiteNodeId(edge); // from_node_id
            Long dep    = PrereqDirection.dependentNodeId(edge);    // to_node_id
            if (nodeSet.contains(prereq) && nodeSet.contains(dep) && !prereq.equals(dep)) {
                adj.get(prereq).add(dep);
                inDegree.merge(dep, 1, Integer::sum);
            }
        }

        // Kahn BFS：layer[n] = max 祖先层号 + 1（最长路径分层）
        Map<Long, Integer> layer = new HashMap<>();
        Queue<Long> queue = new ArrayDeque<>();
        for (Long id : nodeIds) {
            if (inDegree.get(id) == 0) {
                queue.add(id);
                layer.put(id, 0);
            }
        }
        while (!queue.isEmpty()) {
            Long curr = queue.poll();
            int currLayer = layer.getOrDefault(curr, 0);
            for (Long dep : adj.getOrDefault(curr, List.of())) {
                inDegree.merge(dep, -1, Integer::sum);
                layer.merge(dep, currLayer + 1, Math::max);
                if (inDegree.getOrDefault(dep, 0) == 0) {
                    queue.add(dep);
                }
            }
        }
        // 有向环中节点（未被 Kahn 处理）兜底层 0
        for (Long id : nodeIds) {
            layer.putIfAbsent(id, 0);
        }

        // 按 (层号 ASC, nodeId ASC) 稳定排序，取后 ceil(n/2) 为弱集
        List<Long> sorted = new ArrayList<>(nodeIds);
        sorted.sort(Comparator.comparingInt((Long id) -> layer.getOrDefault(id, 0))
                              .thenComparingLong(id -> id));

        int n = sorted.size();
        int backStart = n / 2; // 后 ceil(n/2) 个（n 为偶数时恰好 50%，奇数时多一个）
        return new HashSet<>(sorted.subList(backStart, n));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 着色一致率
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 着色一致率（验收3口径）：同色节点数 / 总节点数。
     *
     * <p>无掌握度行（key 不在 map 中）的节点视为"未学"（level=0），参与分母与分子计算。
     *
     * @param adaptiveLevels nodeId → masteryLevel（有 student_mastery 行时）；缺失 = 0
     * @param fixedLevels    同上，固定卷臂
     * @param allNodeIds     课程全节点 ID（分母）
     * @return 一致率 [0, 1]；空节点集返回 0
     */
    public static double computeAgreementRate(Map<Long, Integer> adaptiveLevels,
                                               Map<Long, Integer> fixedLevels,
                                               Set<Long> allNodeIds) {
        if (allNodeIds == null || allNodeIds.isEmpty()) return 0.0;
        int same = 0;
        for (Long nodeId : allNodeIds) {
            int a = adaptiveLevels.getOrDefault(nodeId, 0);
            int f = fixedLevels.getOrDefault(nodeId, 0);
            if (a == f) same++;
        }
        return (double) same / allNodeIds.size();
    }

    /**
     * 交集一致率：双方都有 mastery 行的节点上的同色率（剥离覆盖差后的纯口径一致性）。
     *
     * @param adaptiveLevels nodeId → masteryLevel（仅含有行的节点）
     * @param fixedLevels    同上，固定卷臂
     * @return 一致率 [0, 1]；交集为空返回 0
     */
    public static double computeIntersectionAgreementRate(Map<Long, Integer> adaptiveLevels,
                                                           Map<Long, Integer> fixedLevels) {
        Set<Long> intersection = new HashSet<>(adaptiveLevels.keySet());
        intersection.retainAll(fixedLevels.keySet());
        if (intersection.isEmpty()) return 0.0;
        int same = 0;
        for (Long nodeId : intersection) {
            if (Objects.equals(adaptiveLevels.get(nodeId), fixedLevels.get(nodeId))) same++;
        }
        return (double) same / intersection.size();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 二分类映射
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 自适应二分类：
     * <ul>
     *   <li>TESTED_PASS / INFERRED_PASS → {@code true}（掌握）</li>
     *   <li>TESTED_FAIL / INFERRED_FAIL → {@code false}（薄弱）</li>
     *   <li>UNKNOWN → {@code null}（排除，不参与准确率分母）</li>
     * </ul>
     */
    public static Boolean binaryClassifyByState(NodeState state) {
        if (state == null) return null;
        return switch (state) {
            case TESTED_PASS, INFERRED_PASS -> Boolean.TRUE;
            case TESTED_FAIL, INFERRED_FAIL -> Boolean.FALSE;
            case UNKNOWN -> null;
        };
    }

    /**
     * 固定卷二分类：score ≥ 50 → {@code true}（掌握），{@literal <} 50 → {@code false}（薄弱）。
     *
     * <p>脚注：冷启动答对下界 = 65（难度1），答错上界 = 35（难度1），中点 = 50，
     * 恰好分隔"答对冷启动"与"答错冷启动"两个区间，语义清晰。
     */
    public static boolean binaryClassifyByScore(double score) {
        return score >= 50.0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 准确率
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 计算二分类准确率（vs. ground truth 弱点集）。
     *
     * <p>ground truth：nodeId ∈ weakNodes → FAIL（false）；否则 → PASS（true）。
     * predictions 中 {@code null} 值（UNKNOWN）跳过，不计入分母。
     *
     * @param predictions nodeId → true（预测掌握）/ false（预测薄弱）/ null（排除）
     * @param weakNodes   ground truth 弱点集
     * @param allNodeIds  遍历范围（分母候选集）
     * @return 准确率 [0, 1]；无有效预测返回 0
     */
    public static double computeAccuracy(Map<Long, Boolean> predictions,
                                          Set<Long> weakNodes,
                                          Set<Long> allNodeIds) {
        int correct = 0, total = 0;
        for (Long nodeId : allNodeIds) {
            Boolean pred = predictions.get(nodeId);
            if (pred == null) continue; // UNKNOWN 排除
            boolean gtPass = !weakNodes.contains(nodeId);
            if (pred.equals(gtPass)) correct++;
            total++;
        }
        return total == 0 ? 0.0 : (double) correct / total;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Markdown 渲染
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 渲染 markdown 评估表格（含表头 + 数据行）。
     *
     * <p>准确率口径说明：
     * <ul>
     *   <li>覆盖内 = 排除自适应 UNKNOWN / 固定无 mastery 行节点，分母 = 有效预测节点数</li>
     *   <li>全图 = 未覆盖节点计为 FAIL 预测，分母 = 全部课程节点数</li>
     *   <li>同口径列方可跨臂比较（覆盖内对覆盖内，全图对全图）</li>
     * </ul>
     *
     * @param results 各画像评估结果（顺序即行顺序）
     * @return 完整 markdown 表格字符串（含末尾换行）
     */
    public static String renderMarkdownTable(List<ProfileResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("| 画像 | 自适应题数 | 固定卷题数 | 自适应覆盖 | 固定覆盖 | UNKNOWN | 着色一致率 | 交集一致率 | 自适应准确率(覆盖内) | 自适应准确率(全图) | 固定准确率(覆盖内) | 固定准确率(全图) |\n");
        sb.append("|------|-----------|-----------|-----------|---------|---------|-----------|-----------|---------------------|------------------|------------------|----------------|\n");
        for (ProfileResult r : results) {
            sb.append(String.format("| %s | %d | %d | %d/%d | %d/%d | %d | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.1f%% |%n",
                    r.profile(),
                    r.adaptiveQuestionCount(), r.fixedQuestionCount(),
                    r.adaptiveCovered(), r.totalNodes(),
                    r.fixedCovered(), r.totalNodes(),
                    r.unknownCount(),
                    r.agreementRate() * 100,
                    r.intersectionAgreementRate() * 100,
                    r.adaptiveAccuracyCovered() * 100,
                    r.adaptiveAccuracyAll() * 100,
                    r.fixedAccuracyCovered() * 100,
                    r.fixedAccuracyAll() * 100));
        }
        return sb.toString();
    }

    /**
     * 生成单行结论（每画像一句，可直接贴答辩稿）。
     *
     * <p>引用覆盖内准确率并附全图数字，同时注明覆盖情况。
     */
    public static String conclusionLine(ProfileResult r) {
        return String.format(
                "%s画像：自适应 %d 题完成 %d/%d 节点分类（UNKNOWN=%d），" +
                "覆盖内准确率 %.1f%%（全图 %.1f%%）；" +
                "固定卷 %d 题覆盖 %d/%d 节点，覆盖内准确率 %.1f%%（全图 %.1f%%）；" +
                "着色一致率 %.1f%%。",
                r.profile(),
                r.adaptiveQuestionCount(),
                r.adaptiveCovered(), r.totalNodes(), r.unknownCount(),
                r.adaptiveAccuracyCovered() * 100, r.adaptiveAccuracyAll() * 100,
                r.fixedQuestionCount(), r.fixedCovered(), r.totalNodes(),
                r.fixedAccuracyCovered() * 100, r.fixedAccuracyAll() * 100,
                r.agreementRate() * 100);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 结果封装
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 单画像评估结果（每画像一行输入 markdown 表）。
     *
     * <p>准确率分为两口径（四列），以保证答辩素材中跨臂比较的对称性：
     * <ul>
     *   <li><b>覆盖内</b>：排除自适应 UNKNOWN / 固定无 mastery 行节点，分母 = 有效预测节点数</li>
     *   <li><b>全图</b>：未覆盖节点计为 FAIL 预测，分母 = 全部课程节点数</li>
     * </ul>
     * 同口径列方可跨臂比较。
     *
     * @param profile                   画像名（全弱/半弱/全强）
     * @param adaptiveQuestionCount     自适应实际用题数（state_json.budgetUsed）
     * @param fixedQuestionCount        固定卷实际下发题数（composePaper 返回数，非标称值）
     * @param adaptiveCovered           自适应非 UNKNOWN 节点数
     * @param fixedCovered              固定卷有 student_mastery 行的节点数
     * @param totalNodes                课程总节点数
     * @param unknownCount              自适应 UNKNOWN 节点数（0 题不可实测节点）
     * @param agreementRate             着色一致率（验收3口径）
     * @param intersectionAgreementRate 交集一致率
     * @param adaptiveAccuracyCovered   自适应覆盖内准确率（UNKNOWN 排除）
     * @param adaptiveAccuracyAll       自适应全图准确率（UNKNOWN → FAIL 补全）
     * @param fixedAccuracyCovered      固定覆盖内准确率（无 mastery 行节点排除）
     * @param fixedAccuracyAll          固定全图准确率（无 mastery 行节点 → FAIL 补全）
     */
    public record ProfileResult(
            String profile,
            int adaptiveQuestionCount,
            int fixedQuestionCount,
            int adaptiveCovered,
            int fixedCovered,
            int totalNodes,
            int unknownCount,
            double agreementRate,
            double intersectionAgreementRate,
            double adaptiveAccuracyCovered,
            double adaptiveAccuracyAll,
            double fixedAccuracyCovered,
            double fixedAccuracyAll
    ) {}
}
