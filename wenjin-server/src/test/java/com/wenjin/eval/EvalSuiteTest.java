package com.wenjin.eval;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.eval.golden.EnabledScenarioStepsFactory;
import com.wenjin.eval.golden.GoldenLoader;
import com.wenjin.eval.report.EvalReportCase;
import com.wenjin.eval.report.EvalReportGenerator;
import com.wenjin.eval.runner.EvalCaseOutcome;
import com.wenjin.eval.runner.EvalEnabledGuard;
import com.wenjin.eval.runner.EvalMode;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.EvalScenarioStatus;
import com.wenjin.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Eval suite entry (M6).
 * <p>Default tests: gate + inventory + golden inventory (no Docker).
 * REPLAY hard asserts live in {@link ReplayScenarioHardAssertTest}.
 * Full LIVE+RECORD batch requires AI keys and is opt-in.
 */
class EvalSuiteTest {

    @Test
    @DisplayName("suite gate: eval-enabled=false → skip path documented")
    void suiteGate_disabled() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setEvalEnabled(false);
        assumeTrue(!EvalEnabledGuard.isEnabled(flags), "guard off");
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
        assertThat(enabled).isEqualTo(EnabledScenarioStepsFactory.enabledCodes().size());
        assertThat(pending).isEqualTo(3); // S18 + M10 S20,S21 (PENDING golden)
        assertThat(docs.stream().map(EvalScenarioDocument::code)).contains("S01", "S09", "S13", "S18", "S19", "S20", "S21");
    }

    @Test
    @DisplayName("ENABLED scenarios have classpath goldens for REPLAY")
    void enabledGoldensPresent() {
        GoldenLoader goldens = new GoldenLoader();
        for (String code : EnabledScenarioStepsFactory.enabledCodes()) {
            assertThat(goldens.loadFromClasspath(code).scenario()).isEqualTo(code);
        }
    }

    @Test
    @DisplayName("writes target/eval-report.md summary (at least empty / inventory)")
    void writesEvalReportMarkdown() throws Exception {
        ScenarioLoader loader = new ScenarioLoader();
        List<EvalReportCase> cases = loader.loadAll().stream()
                .map(d -> {
                    if (d.resolvedStatus() == EvalScenarioStatus.PENDING) {
                        return EvalReportCase.of(EvalCaseOutcome.skipped(
                                d.code(), EvalMode.REPLAY, "PENDING: " + d.pendingReason()));
                    }
                    if (d.resolvedStatus() != EvalScenarioStatus.ENABLED) {
                        return EvalReportCase.of(EvalCaseOutcome.skipped(
                                d.code(), EvalMode.REPLAY, "status=" + d.status()));
                    }
                    // ENABLED detailed hard results written by ReplayScenarioHardAssertTest;
                    // inventory row marks presence for suite-level report.
                    return EvalReportCase.of(EvalCaseOutcome.skipped(
                            d.code(), EvalMode.REPLAY, "inventory-only (see ReplayScenarioHardAssertTest)"));
                })
                .toList();

        Path path = new EvalReportGenerator().writeDefault(cases);
        assertThat(path).exists();
        assertThat(Files.readString(path)).contains("# Eval Report", "S01", "S07");
    }

    /**
     * Optional full suite hook: {@code -Dwenjin.eval.suite=true}.
     * Still does not require Docker; LIVE path needs AI keys separately.
     */
    @Test
    @EnabledIfSystemProperty(named = "wenjin.eval.suite", matches = "true")
    @DisplayName("optional full suite hook (disabled by default)")
    void fullSuite_optIn() {
        assertThat(new ScenarioLoader().loadAll()).isNotEmpty();
        assertThat(EnabledScenarioStepsFactory.enabledCodes()).isNotEmpty();
    }
}
