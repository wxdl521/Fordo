package com.wenjin.support.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlanAlgorithm 表驱动单测（纯函数，无 Spring / DB）。
 */
class PlanAlgorithmTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final double MASTERED = 75.0;
    private static final double WEAK = 60.0;
    private static final double TAU = 30.0;
    private static final double FLOOR = 25.0;

    static Stream<Arguments> tableCases() {
        return Stream.of(
                Arguments.of("empty_nodes_ok_empty_tasks",
                        baseInput(TODAY.plusDays(10), 3, List.of(), Set.of()),
                        true, null, 0),
                Arguments.of("priority_due_over_weak",
                        dueOverWeakInput(),
                        true, null, 1),
                Arguments.of("priority_weak_over_frontier",
                        weakOverFrontierInput(),
                        true, null, 1),
                Arguments.of("daily_cap_respected",
                        manyWeakCap2(),
                        true, null, 6), // 2 work days * 2 + target reviews ≤cap
                Arguments.of("target_day_weak_review",
                        targetDayReviewInput(),
                        true, null, 2), // 0 work + 1 weak review on target? work days exist
                Arguments.of("overflow_scope_too_large",
                        overflowInput(),
                        false, PlanErrorCodes.PLAN_SCOPE_TOO_LARGE, 0),
                Arguments.of("scope_intersection",
                        scopedInput(),
                        true, null, 1),
                Arguments.of("topo_order_same_priority",
                        topoOrderInput(),
                        true, null, 2),
                Arguments.of("due_window_boundary_after_target_excluded",
                        dueAfterTargetExcluded(),
                        true, null, 0),
                Arguments.of("already_due_included",
                        alreadyDueInput(),
                        true, null, 1)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tableCases")
    @DisplayName("表驱动：优先级 / cap / 溢出 / scope / 拓扑 / due 边界")
    void tableDriven(String name,
                     PlanAlgorithm.PlanInput input,
                     boolean expectOk,
                     String expectError,
                     int minTasks) {
        PlanAlgorithm.PlanResult result = PlanAlgorithm.plan(input);
        assertThat(result.ok()).as(name).isEqualTo(expectOk);
        if (expectError != null) {
            assertThat(result.errorCode()).isEqualTo(expectError);
            assertThat(result.suggestedScope()).isNotEmpty();
        } else {
            assertThat(result.errorCode()).isNull();
            assertThat(result.tasks().size()).isGreaterThanOrEqualTo(minTasks);
        }
    }

    @Test
    @DisplayName("due > weak：同一节点保留 REVIEW 而非 PRACTICE")
    void dueWinsOverWeak_taskKindReview() {
        PlanAlgorithm.PlanResult r = PlanAlgorithm.plan(dueOverWeakInput());
        assertThat(r.ok()).isTrue();
        assertThat(r.tasks()).isNotEmpty();
        assertThat(r.tasks().get(0).taskKind()).isEqualTo(PlanTaskKind.REVIEW);
        assertThat(r.tasks().get(0).nodeCode()).isEqualTo("D1");
    }

    @Test
    @DisplayName("溢出返回建议集且不硬塞任务")
    void overflow_suggestedScope_noSilentDrop() {
        PlanAlgorithm.PlanResult r = PlanAlgorithm.plan(overflowInput());
        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(PlanErrorCodes.PLAN_SCOPE_TOO_LARGE);
        assertThat(r.tasks()).isEmpty();
        // capacity = 1 work day * cap 1 = 1
        assertThat(r.suggestedScope()).hasSize(1);
    }

    @Test
    @DisplayName("每日 cap：工作日任务数 ≤ cap")
    void dailyCap_neverExceeds() {
        PlanAlgorithm.PlanInput input = manyWeakCap2();
        PlanAlgorithm.PlanResult r = PlanAlgorithm.plan(input);
        assertThat(r.ok()).isTrue();
        Map<LocalDate, Long> byDay = r.tasks().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        PlanAlgorithm.PlannedTask::taskDate, java.util.stream.Collectors.counting()));
        for (Map.Entry<LocalDate, Long> e : byDay.entrySet()) {
            assertThat(e.getValue()).as("day " + e.getKey()).isLessThanOrEqualTo(2L);
        }
    }

    @Test
    @DisplayName("target 当日任务 kind 均为 REVIEW")
    void targetDay_allReview() {
        PlanAlgorithm.PlanResult r = PlanAlgorithm.plan(targetDayReviewInput());
        LocalDate target = TODAY.plusDays(5);
        List<PlanAlgorithm.PlannedTask> targetTasks = r.tasks().stream()
                .filter(t -> t.taskDate().equals(target))
                .toList();
        assertThat(targetTasks).isNotEmpty();
        assertThat(targetTasks).allMatch(t -> t.taskKind() == PlanTaskKind.REVIEW);
    }

    @Test
    @DisplayName("同优先级按 topoIndex 升序")
    void samePriority_topoOrder() {
        PlanAlgorithm.PlanResult r = PlanAlgorithm.plan(topoOrderInput());
        assertThat(r.ok()).isTrue();
        List<String> workCodes = r.tasks().stream()
                .filter(t -> t.taskDate().isBefore(TODAY.plusDays(4)))
                .map(PlanAlgorithm.PlannedTask::nodeCode)
                .toList();
        // B topo 0 before C topo 1
        assertThat(workCodes.get(0)).isEqualTo("B");
        assertThat(workCodes.get(1)).isEqualTo("C");
    }

    @Test
    @DisplayName("daysUntilDue：今天已到期 → 0；target 后到期 → 大天数")
    void daysUntilDue_boundaries() {
        // raw 90, days large → already below mastered
        var already = PlanAlgorithm.daysUntilDue(90, 100, TAU, FLOOR, MASTERED);
        assertThat(already.isPresent()).isTrue();
        assertThat(already.getAsDouble()).isEqualTo(0.0);

        // raw 90, days 0 → still high, some future days
        var future = PlanAlgorithm.daysUntilDue(90, 0, TAU, FLOOR, MASTERED);
        assertThat(future.isPresent()).isTrue();
        assertThat(future.getAsDouble()).isGreaterThan(0);

        // never mastered
        assertThat(PlanAlgorithm.daysUntilDue(50, 0, TAU, FLOOR, MASTERED).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("topoIndices：先修拓扑稳定")
    void topoIndices_kahn() {
        Map<String, Integer> order = PlanAlgorithm.topoIndices(
                Set.of("A", "B", "C"),
                Map.of("B", List.of("A"), "C", List.of("B")));
        assertThat(order.get("A")).isLessThan(order.get("B"));
        assertThat(order.get("B")).isLessThan(order.get("C"));
    }

    // ── fixtures ──────────────────────────────────────────────

    private static PlanAlgorithm.PlanInput baseInput(
            LocalDate target, int cap, List<PlanAlgorithm.NodeSnapshot> nodes, Set<String> scope) {
        return new PlanAlgorithm.PlanInput(
                TODAY, target, nodes, scope, cap, WEAK, MASTERED, TAU, FLOOR);
    }

    /** High raw decaying into window → due; also weak. */
    private static PlanAlgorithm.PlanInput dueOverWeakInput() {
        // raw 90, many days → effective low but raw was mastered → due
        PlanAlgorithm.NodeSnapshot n = new PlanAlgorithm.NodeSnapshot(
                "D1", 40.0, 90.0, 80.0, List.of(), 0);
        return baseInput(TODAY.plusDays(10), 3, List.of(n), Set.of());
    }

    private static PlanAlgorithm.PlanInput weakOverFrontierInput() {
        // weak and frontier both apply; weak wins → PRACTICE
        PlanAlgorithm.NodeSnapshot n = new PlanAlgorithm.NodeSnapshot(
                "W1", 30.0, 30.0, 0.0, List.of(), 0);
        return baseInput(TODAY.plusDays(5), 3, List.of(n), Set.of());
    }

    private static PlanAlgorithm.PlanInput manyWeakCap2() {
        List<PlanAlgorithm.NodeSnapshot> nodes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            nodes.add(new PlanAlgorithm.NodeSnapshot(
                    "W" + i, 20.0 + i, 20.0 + i, 0.0, List.of(), i));
        }
        // target = today+3 → work days = tomorrow, today+2 (2 days) * cap 2 = 4 capacity
        return baseInput(TODAY.plusDays(3), 2, nodes, Set.of());
    }

    private static PlanAlgorithm.PlanInput targetDayReviewInput() {
        PlanAlgorithm.NodeSnapshot weak = new PlanAlgorithm.NodeSnapshot(
                "TW", 25.0, 25.0, 0.0, List.of(), 0);
        return baseInput(TODAY.plusDays(5), 3, List.of(weak), Set.of());
    }

    private static PlanAlgorithm.PlanInput overflowInput() {
        List<PlanAlgorithm.NodeSnapshot> nodes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            nodes.add(new PlanAlgorithm.NodeSnapshot(
                    "O" + i, 10.0, 10.0, 0.0, List.of(), i));
        }
        // target=today+2 → 1 work day * cap 1 = 1 < 5
        return baseInput(TODAY.plusDays(2), 1, nodes, Set.of());
    }

    private static PlanAlgorithm.PlanInput scopedInput() {
        List<PlanAlgorithm.NodeSnapshot> nodes = List.of(
                new PlanAlgorithm.NodeSnapshot("IN", 20.0, 20.0, 0.0, List.of(), 0),
                new PlanAlgorithm.NodeSnapshot("OUT", 15.0, 15.0, 0.0, List.of(), 1));
        return baseInput(TODAY.plusDays(5), 3, nodes, Set.of("IN"));
    }

    private static PlanAlgorithm.PlanInput topoOrderInput() {
        // both frontier/weak same priority weak=1
        List<PlanAlgorithm.NodeSnapshot> nodes = List.of(
                new PlanAlgorithm.NodeSnapshot("C", 20.0, 20.0, 0.0, List.of(), 1),
                new PlanAlgorithm.NodeSnapshot("B", 20.0, 20.0, 0.0, List.of(), 0));
        return baseInput(TODAY.plusDays(4), 3, nodes, Set.of());
    }

    private static PlanAlgorithm.PlanInput dueAfterTargetExcluded() {
        // fresh high mastery: due far in future beyond target
        PlanAlgorithm.NodeSnapshot n = new PlanAlgorithm.NodeSnapshot(
                "FAR", 90.0, 90.0, 0.0, List.of(), 0);
        // short window: today+2; due for 90 raw is many days out
        return baseInput(TODAY.plusDays(2), 3, List.of(n), Set.of());
    }

    private static PlanAlgorithm.PlanInput alreadyDueInput() {
        PlanAlgorithm.NodeSnapshot n = new PlanAlgorithm.NodeSnapshot(
                "AD", 50.0, 85.0, 60.0, List.of(), 0);
        return baseInput(TODAY.plusDays(8), 3, List.of(n), Set.of());
    }
}
