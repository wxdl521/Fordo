package com.wenjin.eval.runner;

import com.wenjin.eval.hard.HardAssertChecker;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioDriverTest {

    @Test
    @DisplayName("turn 抛出 Error（非 RuntimeException）时 afterScenario 仍被调用，共享覆盖得以恢复")
    void afterScenarioRunsEvenWhenTurnThrowsError() {
        EvalScenarioDocument scenario = new ScenarioLoader().loadByCode("S09").orElseThrow();
        AtomicInteger before = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();
        ScenarioLifecycle hooks = new ScenarioLifecycle() {
            @Override
            public void beforeScenario(EvalScenarioDocument s) {
                before.incrementAndGet();
            }

            @Override
            public EvalCaseOutcome afterScenario(EvalScenarioDocument s, EvalCaseOutcome outcome) {
                after.incrementAndGet();
                return outcome;
            }
        };

        ScenarioRun run = ScenarioDriver.drive(EvalMode.REPLAY, scenario,
                request -> {
                    throw new AssertionError("golden mismatch");
                },
                new HardAssertChecker(), null, hooks);

        assertThat(before.get()).isEqualTo(1);
        assertThat(after.get()).isEqualTo(1);
        assertThat(run.outcome().hardPass()).isFalse();
        assertThat(run.outcome().hardResult().failures())
                .anySatisfy(f -> assertThat(f.toString()).contains("golden mismatch"));
    }
}
