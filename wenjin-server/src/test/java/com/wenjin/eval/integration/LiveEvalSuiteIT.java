package com.wenjin.eval.integration;

import com.wenjin.ai.OpenAiCompatibleCompanionAiClient;
import com.wenjin.eval.runner.EvalHarness;
import com.wenjin.eval.runner.EvalScenarioEnvironment;
import com.wenjin.eval.runner.LiveEvalSuite;
import com.wenjin.eval.report.EvalSuiteReportWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real LIVE eval against the configured model ({@code wenjin.ai.*}). Runs ENABLED scenarios (or the listed
 * ones, PENDING allowed) N times, judges optionally, writes {@code target/eval-live/<ts>/report.md|json}
 * (+ {@code calibration.csv} when judged) and optionally RECORDs goldens.
 * <p>Gate: {@code WENJIN_EVAL_LIVE=true} and a non-empty {@code WENJIN_AI_API_KEY}. Parameters:
 * {@code -Dwenjin.eval.repeat=3}, {@code -Dwenjin.eval.scenarios=S01,S04}, {@code -Dwenjin.eval.judge=true},
 * {@code -Dwenjin.eval.record=true} ({@code -Dwenjin.eval.record-dir}, default
 * {@code src/test/resources/eval/golden}), {@code -Dwenjin.eval.jdbc-url=...} (default Testcontainers).
 * The suite does not fail on scenario failures — the report is the result; it fails only when nothing ran.
 */
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "WENJIN_EVAL_LIVE", matches = "true"),
        @EnabledIfEnvironmentVariable(named = "WENJIN_AI_API_KEY", matches = ".+")
})
class LiveEvalSuiteIT extends EvalDbSupport {

    @Autowired
    private EvalHarness harness;

    @Autowired
    private OpenAiCompatibleCompanionAiClient realClient;

    @Test
    @DisplayName("LIVE suite against the real model → report.md / report.json")
    void liveSuite() {
        harness.aiClient().useBase(realClient);
        int repeat = Integer.parseInt(System.getProperty("wenjin.eval.repeat", "3"));
        String scenarios = System.getProperty("wenjin.eval.scenarios", "");
        List<String> codes = scenarios.isBlank() ? List.of()
                : Arrays.stream(scenarios.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        boolean judge = Boolean.parseBoolean(System.getProperty("wenjin.eval.judge", "false"));
        boolean record = Boolean.parseBoolean(System.getProperty("wenjin.eval.record", "false"));
        EvalScenarioEnvironment.RecordSettings recordSettings = record
                ? new EvalScenarioEnvironment.RecordSettings(
                        Path.of(System.getProperty("wenjin.eval.record-dir", "src/test/resources/eval/golden")),
                        harness.modelName())
                : null;
        if (judge) {
            assertThat(harness.judgeService()).as("judge client configured").isNotNull();
        }

        LiveEvalSuite.Result result = harness.runLive(codes, repeat, judge, recordSettings,
                Path.of(System.getProperty("wenjin.eval.out-dir", EvalSuiteReportWriter.DEFAULT_BASE_DIR)));

        System.out.println(EvalSuiteReportWriter.summaryMarkdown(result.summary()));
        System.out.println("eval report: " + result.outDir().toAbsolutePath());
        assertThat(result.summary().overall().runs()).isPositive();
        assertThat(result.outDir().resolve("report.json")).exists();
    }
}
