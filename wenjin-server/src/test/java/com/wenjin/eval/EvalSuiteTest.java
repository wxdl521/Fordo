package com.wenjin.eval;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.eval.runner.EvalEnabledGuard;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.EvalScenarioStatus;
import com.wenjin.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Minimal eval suite skeleton (M6).
 * <p>Full LIVE/REPLAY batch + Docker is Task 10+; this test only verifies gate + scenario inventory
 * without MySQL/Docker.
 */
class EvalSuiteTest {

    @Test
    @DisplayName("suite gate: eval-enabled=false → skip path documented")
    void suiteGate_disabled() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setEvalEnabled(false);
        assumeTrue(!EvalEnabledGuard.isEnabled(flags), "guard off");
        // Suite would skip all scenarios when flag is false (see Live/Replay executors).
        assertThat(EvalEnabledGuard.SKIP_REASON).contains("eval-enabled=false");
    }

    @Test
    @DisplayName("scenario inventory loads without Docker (ENABLED + PENDING counted)")
    void scenarioInventory_noDocker() {
        ScenarioLoader loader = new ScenarioLoader();
        var docs = loader.loadAll();
        assertThat(docs).isNotEmpty();
        long enabled = docs.stream()
                .filter(d -> d.resolvedStatus() == EvalScenarioStatus.ENABLED)
                .count();
        long pending = docs.stream()
                .filter(d -> d.resolvedStatus() == EvalScenarioStatus.PENDING)
                .count();
        assertThat(enabled + pending).isEqualTo(docs.size());
        assertThat(docs.stream().map(EvalScenarioDocument::code)).contains("S01", "S09");
    }

    /**
     * Placeholder for full suite: only runs when explicitly enabled
     * ({@code -Dwenjin.eval.suite=true}) and still does not require Docker in this skeleton.
     */
    @Test
    @EnabledIfSystemProperty(named = "wenjin.eval.suite", matches = "true")
    @DisplayName("optional full suite hook (disabled by default)")
    void fullSuite_optIn() {
        // Task 10 will drive LIVE RECORD / REPLAY batch here.
        assertThat(new ScenarioLoader().loadAll()).isNotEmpty();
    }
}
