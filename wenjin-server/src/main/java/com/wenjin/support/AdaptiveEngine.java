package com.wenjin.support;

import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 自适应诊断纯算法核心（M2 T2）：节点状态机、双向推断传播、贪心信息最大化选点、终止判定。
 *
 * <p><b>无 Spring / MyBatis 依赖，纯 {@code new} 可用</b>——T3 将其状态序列化进
 * {@code adaptive_diag_session.state_json}，T4 用服务层包装（组探针、判分后调
 * {@link #recordProbeResult}）。引擎不懂题库、不碰数据库、不做 JSON。
 *
 * <h2>状态机与传播规则（02-M2 §2.1 + 任务简报裁决）</h2>
 * <ul>
 *   <li>节点 B 实测通过 ⇒ B 的全体前置祖先（沿 relation_type=1 反向闭包）置 INFERRED_PASS；</li>
 *   <li>节点 A 实测未通过 ⇒ A 的全体后继子孙置 INFERRED_FAIL；</li>
 *   <li>传播到达节点时：实测态永不改动；UNKNOWN 置对应推断态；同方向推断幂等；
 *       <b>相反方向推断 ⇒ 回退 UNKNOWN</b>（两推断互相抵消，留给选题器实测）；</li>
 *   <li>对已推断节点复测：先置 TESTED_*，再照常向对应方向传播（仍遵守上述规则）。</li>
 * </ul>
 *
 * <h2>选题策略（贪心信息最大化）</h2>
 * <pre>gain(n) = |UNKNOWN 祖先(n)| + |UNKNOWN 子孙(n)| + isKey(n)·2</pre>
 * 候选集 = (UNKNOWN ∪ INFERRED_*) ∩ 可实测集；INFERRED_* 候选 gain×0.5 后参与比较。
 * 平手裁决链（完全确定性）：is_key=1 优先 → (入度+出度) 大者优先 → nodeId 小者优先。
 *
 * <h2>终止条件（按 a→b→c 顺序判定，先到先停）</h2>
 * <ol>
 *   <li>(a) {@link TerminationReason#ALL_KNOWN}：无 UNKNOWN 节点；</li>
 *   <li>(b) {@link TerminationReason#BUDGET_EXHAUSTED}：{@code budgetUsed >= budget}；</li>
 *   <li>(c) {@link TerminationReason#NO_TESTABLE}：候选∩可实测集为空。</li>
 * </ol>
 *
 * <h2>方向与图输入约定</h2>
 * <p>前置边方向以 T1 的 {@link PrereqDirection} 结论为准（from_node_id 是 to_node_id 的
 * 前置），端点一律经 {@link PrereqDirection#prerequisiteNodeId} /
 * {@link PrereqDirection#dependentNodeId} 读取。输入边列表应只含 relation_type=1，
 * 引擎仍防御性过滤非前置边、端点缺失/不在节点集内的边与自环。祖先/子孙闭包按需 BFS
 * 现算（42 节点规模毫秒级，不预计算闭包），<b>BFS 带访问标记</b>（防御环）。
 *
 * <h2>状态可恢复（T3/T4 契约）</h2>
 * <p>六参构造器从 {@code existingStates + budgetUsed} 重建（快照应为传播完成后的状态，
 * 恢复时不重放传播）；状态 Map 中不在当前图内的节点 ID 被忽略（容忍图谱漂移），缺失的
 * 节点视为 UNKNOWN。引擎不持有任何静态可变状态，实例线程不安全（每会话单实例使用）。
 *
 * <p>注：{@link #recordProbeResult} 不做候选校验与终止拦截——是否只对
 * {@link #selectNextNode} 返回的节点出探针、终止后是否继续记账，由调用方（T4）负责。
 */
public final class AdaptiveEngine {

    /** 终止原因（{@link #NONE}=会话进行中）。 */
    public enum TerminationReason {
        /** 未终止 */
        NONE,
        /** (a) 无 UNKNOWN 节点 */
        ALL_KNOWN,
        /** (b) 题量预算耗尽 */
        BUDGET_EXHAUSTED,
        /** (c) 无可实测候选节点 */
        NO_TESTABLE
    }

    /** 汇总计数（tested = TESTED_PASS + TESTED_FAIL）。 */
    public record Summary(int tested, int inferredPass, int inferredFail, int unknown) { }

    /** 推断态候选复测的降权系数（裁决 2）。 */
    private static final double INFERRED_RETEST_WEIGHT = 0.5;

    /** is_key=1 节点的 gain 加成（计划公式 isKey·2）。 */
    private static final int KEY_NODE_GAIN_BONUS = 2;

    /** nodeId -> 状态（TreeMap 保证遍历按 nodeId 升序，选点完全确定）。 */
    private final TreeMap<Long, NodeState> states = new TreeMap<>();

    /** 后继 -> 前置集（逆向邻接表：dependent -> prerequisites）。 */
    private final TreeMap<Long, Set<Long>> prereqsOf = new TreeMap<>();

    /** 前置 -> 后继集（正向邻接表：prerequisite -> dependents）。 */
    private final TreeMap<Long, Set<Long>> dependentsOf = new TreeMap<>();

    private final Set<Long> keyNodeIds = new HashSet<>();

    private final Set<Long> testableNodeIds = new HashSet<>();

    private final int budget;

    private int budgetUsed;

    /**
     * 新会话构造器：全节点 UNKNOWN、已用题数 0。
     *
     * @param nodes           图谱节点（取 id / isKey）
     * @param prereqEdges     前置边（relation_type=1；方向经 {@link PrereqDirection} 解读）
     * @param testableNodeIds 可实测节点集（调用方负责计算"有 APPROVED 题的节点"）
     * @param budget          题量预算（默认 15，由调用方传入）
     */
    public AdaptiveEngine(Collection<KgNode> nodes, Collection<KgEdge> prereqEdges,
                          Set<Long> testableNodeIds, int budget) {
        this(nodes, prereqEdges, testableNodeIds, budget, Map.of(), 0);
    }

    /**
     * 恢复构造器：从既有会话状态重建（T3 反序列化后调用）。
     *
     * @param existingStates 节点状态快照（传播完成后的状态；未知节点 ID 被忽略，
     *                       缺失节点视为 UNKNOWN；null 等价空 Map）
     * @param budgetUsed     已用题数
     */
    public AdaptiveEngine(Collection<KgNode> nodes, Collection<KgEdge> prereqEdges,
                          Set<Long> testableNodeIds, int budget,
                          Map<Long, NodeState> existingStates, int budgetUsed) {
        if (budget < 0) {
            throw new IllegalArgumentException("budget 不能为负: " + budget);
        }
        if (budgetUsed < 0) {
            throw new IllegalArgumentException("budgetUsed 不能为负: " + budgetUsed);
        }
        for (KgNode node : nodes) {
            Long id = node.getId();
            if (id == null) {
                throw new IllegalArgumentException("节点缺少主键 id: " + node);
            }
            if (states.put(id, NodeState.UNKNOWN) != null) {
                throw new IllegalArgumentException("重复节点 id: " + id);
            }
            if (Integer.valueOf(1).equals(node.getIsKey())) {
                keyNodeIds.add(id);
            }
        }
        for (KgEdge edge : prereqEdges) {
            if (edge.getRelationType() == null
                    || edge.getRelationType() != PrereqDirection.RELATION_TYPE_PREREQUISITE) {
                continue; // 防御：只认前置边
            }
            Long prereq = PrereqDirection.prerequisiteNodeId(edge);
            Long dependent = PrereqDirection.dependentNodeId(edge);
            if (prereq == null || dependent == null || prereq.equals(dependent)
                    || !states.containsKey(prereq) || !states.containsKey(dependent)) {
                continue; // 防御：端点缺失、自环、悬空边（如跨课程残边）一律忽略
            }
            prereqsOf.computeIfAbsent(dependent, k -> new HashSet<>()).add(prereq);
            dependentsOf.computeIfAbsent(prereq, k -> new HashSet<>()).add(dependent);
        }
        if (testableNodeIds != null) {
            for (Long id : testableNodeIds) {
                if (states.containsKey(id)) {
                    this.testableNodeIds.add(id);
                }
            }
        }
        if (existingStates != null) {
            for (Map.Entry<Long, NodeState> entry : existingStates.entrySet()) {
                if (entry.getValue() != null && states.containsKey(entry.getKey())) {
                    states.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.budget = budget;
        this.budgetUsed = budgetUsed;
    }

    // ── 写入：探针结果 ─────────────────────────────────────────────────────

    /**
     * 记录一次探针实测结果：记账 → 置 TESTED_* → 沿对应方向传播推断。
     *
     * @param nodeId        实测节点（必须在图内）
     * @param pass          探针是否通过
     * @param questionsUsed 该探针实际消耗题数（2/1 由调用方决定，引擎只记账）
     */
    public void recordProbeResult(long nodeId, boolean pass, int questionsUsed) {
        if (!states.containsKey(nodeId)) {
            throw new IllegalArgumentException("未知节点 id: " + nodeId);
        }
        if (questionsUsed < 0) {
            throw new IllegalArgumentException("questionsUsed 不能为负: " + questionsUsed);
        }
        budgetUsed += questionsUsed;
        states.put(nodeId, pass ? NodeState.TESTED_PASS : NodeState.TESTED_FAIL);
        if (pass) {
            propagate(nodeId, prereqsOf, NodeState.INFERRED_PASS);
        } else {
            propagate(nodeId, dependentsOf, NodeState.INFERRED_FAIL);
        }
    }

    /** BFS 闭包传播（带访问标记，防御环；穿透实测节点但不改动其状态）。 */
    private void propagate(long startId, Map<Long, Set<Long>> adjacency, NodeState inferred) {
        Set<Long> visited = new HashSet<>();
        visited.add(startId);
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(startId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            for (Long next : adjacency.getOrDefault(current, Set.of())) {
                if (visited.add(next)) {
                    applyInference(next, inferred);
                    queue.add(next);
                }
            }
        }
    }

    /** 传播冲突语义（裁决 1）：实测永不改动；UNKNOWN 置推断；同向幂等；反向回退 UNKNOWN。 */
    private void applyInference(Long nodeId, NodeState inferred) {
        NodeState current = states.get(nodeId);
        if (current.isTested()) {
            return;
        }
        if (current == NodeState.UNKNOWN) {
            states.put(nodeId, inferred);
            return;
        }
        if (current == inferred) {
            return;
        }
        states.put(nodeId, NodeState.UNKNOWN);
    }

    // ── 选点 ───────────────────────────────────────────────────────────────

    /**
     * 贪心信息最大化选点。未终止时必有返回值；已终止返回 {@link Optional#empty()}。
     *
     * <p>确定性保证：候选按 nodeId 升序遍历，仅在严格更优（gain 大 / 平手时 is_key
     * 胜 / 再平手时度数大）时替换，因此全平手时保留最小 nodeId。
     */
    public Optional<Long> selectNextNode() {
        if (isTerminated()) {
            return Optional.empty();
        }
        Long bestId = null;
        double bestGain = -1;
        boolean bestKey = false;
        int bestDegree = -1;
        for (Map.Entry<Long, NodeState> entry : states.entrySet()) {
            if (entry.getValue().isTested()) {
                continue;
            }
            Long id = entry.getKey();
            if (!testableNodeIds.contains(id)) {
                continue;
            }
            double gain = rawGain(id);
            if (entry.getValue().isInferred()) {
                gain *= INFERRED_RETEST_WEIGHT;
            }
            boolean key = keyNodeIds.contains(id);
            int degree = degreeOf(id);
            boolean better = bestId == null
                    || gain > bestGain
                    || (gain == bestGain && key && !bestKey)
                    || (gain == bestGain && key == bestKey && degree > bestDegree);
            if (better) {
                bestId = id;
                bestGain = gain;
                bestKey = key;
                bestDegree = degree;
            }
        }
        return Optional.ofNullable(bestId);
    }

    /** gain(n) = |UNKNOWN 祖先| + |UNKNOWN 子孙| + isKey·2（未降权的原始值）。 */
    private double rawGain(long nodeId) {
        int gain = countUnknownReachable(nodeId, prereqsOf)
                + countUnknownReachable(nodeId, dependentsOf);
        if (keyNodeIds.contains(nodeId)) {
            gain += KEY_NODE_GAIN_BONUS;
        }
        return gain;
    }

    /** BFS 闭包内 UNKNOWN 节点计数（不含起点，带访问标记）。 */
    private int countUnknownReachable(long startId, Map<Long, Set<Long>> adjacency) {
        Set<Long> visited = new HashSet<>();
        visited.add(startId);
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(startId);
        int count = 0;
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            for (Long next : adjacency.getOrDefault(current, Set.of())) {
                if (visited.add(next)) {
                    if (states.get(next) == NodeState.UNKNOWN) {
                        count++;
                    }
                    queue.add(next);
                }
            }
        }
        return count;
    }

    /** 入度+出度（仅计前置边，平手裁决用）。 */
    private int degreeOf(long nodeId) {
        return prereqsOf.getOrDefault(nodeId, Set.of()).size()
                + dependentsOf.getOrDefault(nodeId, Set.of()).size();
    }

    // ── 查询 ───────────────────────────────────────────────────────────────

    /** 单节点状态（未知节点 id 抛 {@link IllegalArgumentException}）。 */
    public NodeState stateOf(long nodeId) {
        NodeState state = states.get(nodeId);
        if (state == null) {
            throw new IllegalArgumentException("未知节点 id: " + nodeId);
        }
        return state;
    }

    /** 全节点状态快照（按 nodeId 升序的不可变副本，可直接作为 T3 的持久化输入）。 */
    public Map<Long, NodeState> statesView() {
        return Collections.unmodifiableMap(new TreeMap<>(states));
    }

    public int getBudget() {
        return budget;
    }

    public int getBudgetUsed() {
        return budgetUsed;
    }

    /** 终止原因（按 a→b→c 顺序判定；NONE=进行中）。 */
    public TerminationReason terminationReason() {
        if (!states.containsValue(NodeState.UNKNOWN)) {
            return TerminationReason.ALL_KNOWN;
        }
        if (budgetUsed >= budget) {
            return TerminationReason.BUDGET_EXHAUSTED;
        }
        if (!hasTestableCandidate()) {
            return TerminationReason.NO_TESTABLE;
        }
        return TerminationReason.NONE;
    }

    public boolean isTerminated() {
        return terminationReason() != TerminationReason.NONE;
    }

    /** 候选集（UNKNOWN ∪ INFERRED_*）∩ 可实测集是否非空。 */
    private boolean hasTestableCandidate() {
        for (Map.Entry<Long, NodeState> entry : states.entrySet()) {
            if (!entry.getValue().isTested() && testableNodeIds.contains(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    /** 汇总计数（tested / inferredPass / inferredFail / unknown）。 */
    public Summary summary() {
        int tested = 0;
        int inferredPass = 0;
        int inferredFail = 0;
        int unknown = 0;
        for (NodeState state : states.values()) {
            switch (state) {
                case TESTED_PASS, TESTED_FAIL -> tested++;
                case INFERRED_PASS -> inferredPass++;
                case INFERRED_FAIL -> inferredFail++;
                case UNKNOWN -> unknown++;
            }
        }
        return new Summary(tested, inferredPass, inferredFail, unknown);
    }
}
