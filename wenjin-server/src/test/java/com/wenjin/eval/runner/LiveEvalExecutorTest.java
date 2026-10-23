package com.wenjin.eval.runner;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.eval.collect.CollectedReply;
import com.wenjin.eval.fixture.EvalFixture;
import com.wenjin.eval.fixture.EvalWorld;
import com.wenjin.eval.fixture.InMemoryEvalMasteryPort;
import com.wenjin.eval.fixture.InMemoryEvalReviewQueuePort;
import com.wenjin.eval.fixture.InMemoryEvalSessionPort;
import com.wenjin.eval.fixture.MapEvalNodeLookup;
import com.wenjin.eval.hard.EvalStepView;
import com.wenjin.eval.hard.HardAssertChecker;
import com.wenjin.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE executor skeleton: load S09 → script turns → hard asserts → result structure.
 * Uses mocked CompanionTurnRunner (no Docker / no real AI).
 */
class LiveEvalExecutorTest {

    @Test
    @DisplayName("S09 空历史：mock turn → hard assert pass → 结果结构完整")
    void s09_emptyHistory_hardPass_structure() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setEvalEnabled(true);

        AtomicInteger turns = new AtomicInteger();
        CompanionTurnRunner runner = request -> {
            turns.incrementAndGet();
            assertThat(request.message()).isNotBlank();
            assertThat(request.conversationId()).isNull(); // first (only) turn
            CollectedReply reply = new CollectedReply(
                    "你好！可以从课程地图的基础节点开始学起。",
                    List.of(),
                    List.of());
            List<EvalStepView> steps = List.of(
                    new EvalStepView("LLM_CALL", "phase1", 0, "{}", "{\"type\":\"final\"}"),
                    new EvalStepView("LLM_CALL", "phase2", 0, null, reply.text()));
            return new CompanionTurnRunner.TurnResult(1001L, 9001L, reply, steps);
        };

        EvalWorld world = new EvalWorld(1L, 2L, 3L, "EVAL", java.util.Set.of("KT01", "KT02"));
        EvalFixture fixture = new EvalFixture(
                world,
                new InMemoryEvalMasteryPort(),
                new InMemoryEvalReviewQueuePort(),
                new InMemoryEvalSessionPort(),
                new MapEvalNodeLookup(3L, Map.of("KT01", 1L, "KT02", 2L)));

        LiveEvalExecutor executor = new LiveEvalExecutor(
                new ScenarioLoader(),
                runner,
                new HardAssertChecker(),
                flags,
                fixture);

        EvalCaseOutcome outcome = executor.run("S09");

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.scenarioCode()).isEqualTo("S09");
        assertThat(outcome.mode()).isEqualTo(EvalMode.LIVE);
        assertThat(outcome.hardPass()).isTrue();
        assertThat(outcome.hardResult()).isNotNull();
        assertThat(outcome.hardResult().passed()).isTrue();
        assertThat(outcome.conversationId()).isEqualTo(1001L);
        assertThat(outcome.runId()).isEqualTo(9001L);
        assertThat(outcome.finalResponse()).contains("课程地图");
        assertThat(turns.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("eval-enabled=false → 整体 skip，不调用 turn runner")
    void evalDisabled_skips() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setEvalEnabled(false);

        AtomicInteger turns = new AtomicInteger();
        CompanionTurnRunner runner = request -> {
            turns.incrementAndGet();
            throw new AssertionError("should not run");
        };

        LiveEvalExecutor executor = new LiveEvalExecutor(
                new ScenarioLoader(),
                runner,
                new HardAssertChecker(),
                flags);

        EvalCaseOutcome outcome = executor.run("S09");

        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.skipReason()).isEqualTo(EvalEnabledGuard.SKIP_REASON);
        assertThat(outcome.hardPass()).isFalse();
        assertThat(turns.get()).isZero();
    }

    @Test
    @DisplayName("mustCallTools 失败时 hardPass=false 且 failures 含检查项")
    void hardAssertFailure_propagates() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setEvalEnabled(true);

        CompanionTurnRunner runner = request -> new CompanionTurnRunner.TurnResult(
                1L,
                2L,
                new CollectedReply("no tools used but required", List.of(), List.of()),
                List.of(new EvalStepView("LLM_CALL", "phase2", 0, null, "no tools used but required")));

        // S01 requires query_mastery
        LiveEvalExecutor executor = new LiveEvalExecutor(
                new ScenarioLoader(),
                runner,
                new HardAssertChecker(),
                flags,
                new EvalFixture(
                        new EvalWorld(1L, 2L, 3L, "E", java.util.Set.of("SE-3.2", "SE-2.1")),
                        new InMemoryEvalMasteryPort(),
                        new InMemoryEvalReviewQueuePort(),
                        new InMemoryEvalSessionPort(),
                        new MapEvalNodeLookup(3L, Map.of("SE-3.2", 1L, "SE-2.1", 2L))));

        EvalCaseOutcome outcome = executor.run("S01");

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.hardPass()).isFalse();
        assertThat(outcome.hardResult().failures())
                .extracting(f -> f.check())
                .contains("mustCallAnyOf");
    }

    @Test
    @DisplayName("EvalEnabledGuard 布尔语义")
    void guardHelpers() {
        assertThat(EvalEnabledGuard.isEnabled((FeatureFlagsProperties) null)).isFalse();
        FeatureFlagsProperties on = new FeatureFlagsProperties();
        on.setEvalEnabled(true);
        assertThat(EvalEnabledGuard.isEnabled(on)).isTrue();
        assertThat(EvalEnabledGuard.isEnabled(Boolean.FALSE)).isFalse();
        assertThat(EvalEnabledGuard.isEnabled(Boolean.TRUE)).isTrue();
    }
}
