package com.wenjin.eval.runner;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.eval.collect.CollectedReply;
import com.wenjin.eval.fixture.EvalFixture;
import com.wenjin.eval.fixture.EvalWorld;
import com.wenjin.eval.fixture.InMemoryEvalMasteryPort;
import com.wenjin.eval.fixture.InMemoryEvalReviewQueuePort;
import com.wenjin.eval.fixture.InMemoryEvalSessionPort;
import com.wenjin.eval.fixture.MapEvalNodeLookup;
import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.golden.GoldenRecorder;
import com.wenjin.eval.hard.EvalStepView;
import com.wenjin.eval.hard.HardAssertChecker;
import com.wenjin.eval.replay.ReplayCompanionAiClient;
import com.wenjin.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayEvalExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("REPLAY mode outcome + hard pass with mock turns")
    void replayMode_structure() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setEvalEnabled(true);

        AtomicInteger turns = new AtomicInteger();
        CompanionTurnRunner runner = request -> {
            turns.incrementAndGet();
            CollectedReply reply = new CollectedReply("replay answer", List.of(), List.of());
            return new CompanionTurnRunner.TurnResult(
                    11L, 22L, reply,
                    List.of(new EvalStepView("LLM_CALL", "phase2", 0, null, "replay answer")));
        };

        EvalFixture fixture = new EvalFixture(
                new EvalWorld(1L, 2L, 3L, "EVAL", java.util.Set.of("KT01")),
                new InMemoryEvalMasteryPort(),
                new InMemoryEvalReviewQueuePort(),
                new InMemoryEvalSessionPort(),
                new MapEvalNodeLookup(3L, Map.of("KT01", 1L)));

        ReplayEvalExecutor executor = new ReplayEvalExecutor(
                new ScenarioLoader(), runner, new HardAssertChecker(), flags, fixture);

        EvalCaseOutcome outcome = executor.run("S09");

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.mode()).isEqualTo(EvalMode.REPLAY);
        assertThat(outcome.hardPass()).isTrue();
        assertThat(outcome.finalResponse()).isEqualTo("replay answer");
        assertThat(turns.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("eval-enabled=false → skip REPLAY")
    void disabled_skips() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setEvalEnabled(false);
        AtomicInteger turns = new AtomicInteger();
        ReplayEvalExecutor executor = new ReplayEvalExecutor(
                new ScenarioLoader(),
                req -> {
                    turns.incrementAndGet();
                    throw new AssertionError("no");
                },
                new HardAssertChecker(),
                flags);

        EvalCaseOutcome outcome = executor.run("S09");
        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.mode()).isEqualTo(EvalMode.REPLAY);
        assertThat(turns.get()).isZero();
    }

    @Test
    @DisplayName("loadReplayClient from written golden file")
    void loadReplayClient_fromPath() {
        GoldenFile golden = new GoldenFile("S09", 2, Instant.parse("2026-07-20T00:00:00Z").toString(),
                "deepseek-chat", true, List.of(new GoldenFile.GoldenTurn(List.of(
                        new GoldenFile.GoldenRound("welcome", List.of(), null)))));
        Path path = GoldenRecorder.defaultGoldenPath(tempDir, "S09");
        new GoldenRecorder().write(golden, path);

        ReplayEvalExecutor executor = new ReplayEvalExecutor(
                new ScenarioLoader(),
                req -> new CompanionTurnRunner.TurnResult(1L, 1L, new CollectedReply("", List.of(), List.of()), List.of()),
                new HardAssertChecker(),
                enabledFlags());

        ReplayCompanionAiClient client = executor.loadReplayClient(path);
        StringBuilder sb = new StringBuilder();
        client.streamTurn(List.of(), List.of(), com.wenjin.ai.CompanionAiClient.ToolChoice.AUTO, sb::append);
        assertThat(sb.toString()).isEqualTo("welcome");
        assertThat(client.verifyFullyConsumed()).isEmpty();
    }

    @Test
    @DisplayName("turn exception → execution hard failure; lifecycle before/after always run; metrics summed")
    void turnException_reportedAndLifecycleRuns() {
        java.util.List<String> events = new java.util.ArrayList<>();
        ScenarioLifecycle lifecycle = new ScenarioLifecycle() {
            @Override
            public void beforeScenario(com.wenjin.eval.scenario.EvalScenarioDocument s) {
                events.add("before:" + s.code());
            }

            @Override
            public EvalCaseOutcome afterScenario(com.wenjin.eval.scenario.EvalScenarioDocument s, EvalCaseOutcome o) {
                events.add("after:" + s.code());
                return o;
            }
        };
        AtomicInteger turns = new AtomicInteger();
        CompanionTurnRunner runner = req -> {
            if (turns.incrementAndGet() == 2) {
                throw new IllegalStateException("Golden rounds exhausted — need re-record");
            }
            return new CompanionTurnRunner.TurnResult(5L, 6L, new CollectedReply("ok", List.of(), List.of()),
                    List.of(new EvalStepView("LLM_CALL", "llm_round", 0, "d", "{}", 1, 10, 2, 5L, null)), 40L, 100L);
        };
        ReplayEvalExecutor executor = new ReplayEvalExecutor(new ScenarioLoader(), runner, new HardAssertChecker(),
                enabledFlags(), null, null, lifecycle);

        ScenarioRun run = executor.runDetailed(new ScenarioLoader().loadByCode("S01").orElseThrow());

        assertThat(events).containsExactly("before:S01", "after:S01");
        assertThat(run.outcome().hardPass()).isFalse();
        assertThat(run.outcome().hardResult().failures()).first()
                .satisfies(f -> assertThat(f.check()).isEqualTo("execution"))
                .satisfies(f -> assertThat(f.message()).contains("need re-record"));
        assertThat(run.outcome().metrics().ttftMs()).isEqualTo(40L);
        assertThat(run.outcome().metrics().tokensIn()).isEqualTo(10);
        assertThat(run.exchanges()).hasSize(2);
        assertThat(run.transcript()).contains("ok");
    }

    private static FeatureFlagsProperties enabledFlags() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setEvalEnabled(true);
        return flags;
    }
}
