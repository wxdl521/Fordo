package com.wenjin.support.plan;

import com.wenjin.service.MasteryDecayCalculator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * 确定性学习计划生成（LLM 零参与任务序列）。
 * <p>
 * 优先级 due &gt; weak &gt; frontier；同优先级按拓扑序（topoIndex 升序，再 nodeCode）。
 * 逐日填充 [明天, target−1]，每日 ≤ dailyTaskCap；target 当日为总复习日（weak→REVIEW）。
 * 候选装不进工作日 → {@link PlanErrorCodes#PLAN_SCOPE_TOO_LARGE} + 最小可行节点建议。
 */
public final class PlanAlgorithm {

    private PlanAlgorithm() {}

    /**
     * 候选节点快照（纯数据；算法不访问 DB / Clock）。
     *
     * @param nodeCode          节点编码
     * @param effective         当前有效掌握分（0–100 口径）
     * @param raw               原始掌握分
     * @param daysSinceEvidence 距上次证据天数
     * @param prereqCodes       直接先修 node_code 列表
     * @param topoIndex         图谱拓扑序（越小越靠前）
     */
    public record NodeSnapshot(
            String nodeCode,
            double effective,
            double raw,
            double daysSinceEvidence,
            List<String> prereqCodes,
            int topoIndex
    ) {
        public NodeSnapshot {
            Objects.requireNonNull(nodeCode, "nodeCode");
            prereqCodes = prereqCodes == null ? List.of() : List.copyOf(prereqCodes);
        }
    }

    /**
     * @param today              算法「今天」（由调用方从 Clock 推导）
     * @param targetDate         考试/目标日（须 &gt; today）
     * @param nodes              全量或已过滤的节点快照
     * @param scopeNodes         非空时与候选取交集；空/null=全图
     * @param dailyTaskCap       每日任务上限（≥1）
     * @param weakThreshold      weak 判定：effective &lt; 此值（0–100）
     * @param masteredThreshold  已掌握阈值（0–100）
     * @param tauDays            衰减 τ
     * @param floor              衰减地板
     */
    public record PlanInput(
            LocalDate today,
            LocalDate targetDate,
            List<NodeSnapshot> nodes,
            Set<String> scopeNodes,
            int dailyTaskCap,
            double weakThreshold,
            double masteredThreshold,
            double tauDays,
            double floor
    ) {
        public PlanInput {
            Objects.requireNonNull(today, "today");
            Objects.requireNonNull(targetDate, "targetDate");
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            scopeNodes = scopeNodes == null || scopeNodes.isEmpty()
                    ? Set.of()
                    : Set.copyOf(scopeNodes);
        }
    }

    public record PlannedTask(LocalDate taskDate, String nodeCode, int taskKind) {
        public PlannedTask {
            Objects.requireNonNull(taskDate, "taskDate");
            Objects.requireNonNull(nodeCode, "nodeCode");
        }
    }

    /**
     * @param ok              true=任务序列可用；false 时 errorCode 非空
     * @param errorCode       如 PLAN_SCOPE_TOO_LARGE
     * @param suggestedScope  溢出时的最小可行节点集（按优先级截断）
     * @param tasks           生成的任务（含 target 日复习）
     * @param atRisk          重排时剩余容量不足标志（本纯函数恒 false；scheduler 可改）
     */
    public record PlanResult(
            boolean ok,
            String errorCode,
            List<String> suggestedScope,
            List<PlannedTask> tasks,
            boolean atRisk
    ) {
        public PlanResult {
            suggestedScope = suggestedScope == null ? List.of() : List.copyOf(suggestedScope);
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }

        public static PlanResult success(List<PlannedTask> tasks) {
            return new PlanResult(true, null, List.of(), tasks, false);
        }

        public static PlanResult tooLarge(List<String> suggested, List<PlannedTask> partialOrEmpty) {
            return new PlanResult(false, PlanErrorCodes.PLAN_SCOPE_TOO_LARGE,
                    suggested, partialOrEmpty, false);
        }
    }

    /** 内部候选：priority 0=due,1=weak,2=frontier。 */
    record Candidate(String nodeCode, int priority, int taskKind, double effective, int topoIndex) {}

    public static PlanResult plan(PlanInput input) {
        Objects.requireNonNull(input, "input");
        if (!input.targetDate().isAfter(input.today())) {
            return PlanResult.tooLarge(List.of(), List.of());
        }
        int cap = Math.max(1, input.dailyTaskCap());
        LocalDate tomorrow = input.today().plusDays(1);
        LocalDate lastWork = input.targetDate().minusDays(1);

        List<NodeSnapshot> scoped = applyScope(input.nodes(), input.scopeNodes());
        Map<String, Double> effectiveByCode = new HashMap<>();
        for (NodeSnapshot n : scoped) {
            effectiveByCode.put(n.nodeCode(), n.effective());
        }

        List<Candidate> candidates = buildCandidates(scoped, effectiveByCode, input, tomorrow, input.targetDate());
        candidates.sort(Comparator
                .comparingInt(Candidate::priority)
                .thenComparingInt(Candidate::topoIndex)
                .thenComparing(Candidate::nodeCode));

        // 工作日容量
        List<LocalDate> workDays = workDays(tomorrow, lastWork);
        int capacity = workDays.size() * cap;

        if (candidates.size() > capacity) {
            List<String> suggested = candidates.stream()
                    .limit(capacity)
                    .map(Candidate::nodeCode)
                    .toList();
            return PlanResult.tooLarge(suggested, List.of());
        }

        List<PlannedTask> tasks = new ArrayList<>();
        int idx = 0;
        for (LocalDate day : workDays) {
            for (int slot = 0; slot < cap && idx < candidates.size(); slot++, idx++) {
                Candidate c = candidates.get(idx);
                tasks.add(new PlannedTask(day, c.nodeCode(), c.taskKind()));
            }
        }

        // target 日：全部 weak 节点 REVIEW，按 effective 升序，截断 cap
        List<NodeSnapshot> weakForReview = scoped.stream()
                .filter(n -> n.effective() < input.weakThreshold())
                .sorted(Comparator
                        .comparingDouble(NodeSnapshot::effective)
                        .thenComparingInt(NodeSnapshot::topoIndex)
                        .thenComparing(NodeSnapshot::nodeCode))
                .toList();
        int reviewSlots = 0;
        for (NodeSnapshot w : weakForReview) {
            if (reviewSlots >= cap) {
                break;
            }
            tasks.add(new PlannedTask(input.targetDate(), w.nodeCode(), PlanTaskKind.REVIEW));
            reviewSlots++;
        }

        return PlanResult.success(tasks);
    }

    /**
     * 预测节点在 [windowStart, windowEnd] 内是否「到期复习」。
     * <p>raw 未达 mastered 的节点不是 due；effective 已低于 mastered 视为已到期（days=0）；
     * 否则解衰减方程得到额外天数，看到期日落在窗口内。
     */
    public static boolean isDueInWindow(
            double raw,
            double daysSinceEvidence,
            double tauDays,
            double floor,
            double masteredThreshold,
            LocalDate today,
            LocalDate windowStart,
            LocalDate windowEnd) {
        OptionalDouble dueDays = daysUntilDue(raw, daysSinceEvidence, tauDays, floor, masteredThreshold);
        if (dueDays.isEmpty()) {
            return false;
        }
        LocalDate dueDate = today.plusDays((long) Math.ceil(dueDays.getAsDouble()));
        // already due → treat as tomorrow earliest for packing window
        if (dueDays.getAsDouble() <= 0) {
            dueDate = today.plusDays(1);
        }
        return !dueDate.isBefore(windowStart) && !dueDate.isAfter(windowEnd);
    }

    /**
     * 距「有效分跌破 masteredThreshold」还需的天数；raw 未达 mastered 返回 empty。
     * 已跌破返回 0。
     */
    public static OptionalDouble daysUntilDue(
            double raw,
            double daysSinceEvidence,
            double tauDays,
            double floor,
            double masteredThreshold) {
        if (raw < masteredThreshold) {
            return OptionalDouble.empty();
        }
        double effective = MasteryDecayCalculator.effectiveScore(raw, daysSinceEvidence, tauDays, floor);
        if (effective < masteredThreshold) {
            return OptionalDouble.of(0.0);
        }
        if (raw <= floor || tauDays <= 0) {
            return OptionalDouble.empty();
        }
        // mastered = floor + (raw-floor)*exp(-(d0+extra)/tau)
        // exp(-(d0+extra)/tau) = (mastered-floor)/(raw-floor)
        double ratio = (masteredThreshold - floor) / (raw - floor);
        if (ratio <= 0 || ratio >= 1) {
            return OptionalDouble.empty();
        }
        double totalDays = -tauDays * Math.log(ratio);
        double extra = Math.max(0.0, totalDays - Math.max(0.0, daysSinceEvidence));
        return OptionalDouble.of(extra);
    }

    private static List<NodeSnapshot> applyScope(List<NodeSnapshot> nodes, Set<String> scope) {
        if (scope == null || scope.isEmpty()) {
            return nodes;
        }
        List<NodeSnapshot> out = new ArrayList<>();
        for (NodeSnapshot n : nodes) {
            if (scope.contains(n.nodeCode())) {
                out.add(n);
            }
        }
        return out;
    }

    private static List<Candidate> buildCandidates(
            List<NodeSnapshot> scoped,
            Map<String, Double> effectiveByCode,
            PlanInput input,
            LocalDate windowStart,
            LocalDate windowEnd) {
        // priority map: lower wins
        Map<String, Candidate> best = new HashMap<>();
        for (NodeSnapshot n : scoped) {
            // due
            if (isDueInWindow(n.raw(), n.daysSinceEvidence(), input.tauDays(), input.floor(),
                    input.masteredThreshold(), input.today(), windowStart, windowEnd)) {
                putBest(best, new Candidate(n.nodeCode(), 0, PlanTaskKind.REVIEW,
                        n.effective(), n.topoIndex()));
            }
            // weak
            if (n.effective() < input.weakThreshold()) {
                putBest(best, new Candidate(n.nodeCode(), 1, PlanTaskKind.PRACTICE,
                        n.effective(), n.topoIndex()));
            }
            // frontier
            if (isFrontier(n, effectiveByCode, input.masteredThreshold())) {
                putBest(best, new Candidate(n.nodeCode(), 2, PlanTaskKind.LEARN,
                        n.effective(), n.topoIndex()));
            }
        }
        return new ArrayList<>(best.values());
    }

    private static void putBest(Map<String, Candidate> best, Candidate c) {
        Candidate prev = best.get(c.nodeCode());
        if (prev == null || c.priority() < prev.priority()) {
            best.put(c.nodeCode(), c);
        }
    }

    private static boolean isFrontier(NodeSnapshot n, Map<String, Double> effectiveByCode, double mastered) {
        if (n.effective() >= mastered) {
            return false;
        }
        for (String p : n.prereqCodes()) {
            double pe = effectiveByCode.getOrDefault(p, 0.0);
            if (pe < mastered) {
                return false;
            }
        }
        return true;
    }

    private static List<LocalDate> workDays(LocalDate start, LocalDate endInclusive) {
        if (endInclusive.isBefore(start)) {
            return List.of();
        }
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(endInclusive); d = d.plusDays(1)) {
            days.add(d);
        }
        return days;
    }

    /**
     * 由先修边计算拓扑序（Kahn）；无边节点 topoIndex=0 级并列，稳定按 nodeCode 排序后赋序。
     *
     * @param nodeCodes 全部 node_code
     * @param prereqOf  dependent → list of prereq codes
     * @return nodeCode → topo index
     */
    public static Map<String, Integer> topoIndices(Set<String> nodeCodes, Map<String, List<String>> prereqOf) {
        Map<String, Integer> indeg = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (String c : nodeCodes) {
            indeg.put(c, 0);
        }
        for (Map.Entry<String, List<String>> e : prereqOf.entrySet()) {
            String dep = e.getKey();
            if (!nodeCodes.contains(dep)) {
                continue;
            }
            for (String p : e.getValue()) {
                if (!nodeCodes.contains(p)) {
                    continue;
                }
                indeg.merge(dep, 1, Integer::sum);
                dependents.computeIfAbsent(p, k -> new ArrayList<>()).add(dep);
            }
        }
        List<String> ready = new ArrayList<>();
        for (Map.Entry<String, Integer> e : indeg.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        Collections.sort(ready);
        Map<String, Integer> order = new HashMap<>();
        int idx = 0;
        while (!ready.isEmpty()) {
            String n = ready.remove(0);
            order.put(n, idx++);
            List<String> next = dependents.getOrDefault(n, List.of());
            List<String> newly = new ArrayList<>();
            for (String d : next) {
                int v = indeg.merge(d, -1, Integer::sum);
                if (v == 0) {
                    newly.add(d);
                }
            }
            Collections.sort(newly);
            ready.addAll(newly);
            Collections.sort(ready);
        }
        // cycles / leftovers
        for (String c : nodeCodes) {
            order.putIfAbsent(c, idx++);
        }
        return order;
    }

    /** 从候选列表提取去重 node_code（保序）。 */
    public static List<String> distinctNodeCodes(List<PlannedTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (PlannedTask t : tasks) {
            seen.add(t.nodeCode());
        }
        return List.copyOf(seen);
    }
}
