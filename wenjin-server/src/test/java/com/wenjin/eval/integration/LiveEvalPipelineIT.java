package com.wenjin.eval.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.golden.GoldenLoader;
import com.wenjin.eval.judge.JudgeService;
import com.wenjin.eval.metrics.EvalSuiteSummary;
import com.wenjin.eval.report.EvalSuiteReportWriter;
import com.wenjin.eval.runner.EvalHarness;
import com.wenjin.eval.runner.EvalScenarioEnvironment;
import com.wenjin.eval.runner.LiveEvalExecutor;
import com.wenjin.eval.runner.LiveEvalSuite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The LIVE pipeline end to end without a model key: same harness, fixture, turn runner, fault injection,
 * RECORD, judge, aggregation and report writer as {@code LiveEvalSuiteIT} — only the base AI client is a
 * golden-scripted stub and the judge is a fake. Proves a key is the only missing piece for a real LIVE run.
 * <p>Gated by {@code WENJIN_EVAL_IT=true}; DB = Testcontainers or {@code -Dwenjin.eval.jdbc-url}.
 */
@EnabledIfEnvironmentVariable(named = "WENJIN_EVAL_IT", matches = "true")
class LiveEvalPipelineIT extends EvalDbSupport {

    private static final Pattern DIM_KEY = Pattern.compile("key=([A-Za-z_]+)");

    @Autowired
    private EvalHarness harness;

    @TempDir
    Path tempDir;

    /** Fake judge: scores every requested dimension 4. */
    private static String fakeJudge(String prompt) {
        Matcher m = DIM_KEY.matcher(prompt);
        List<String> scores = new ArrayList<>();
        while (m.find()) {
            scores.add("{\"key\":\"" + m.group(1) + "\",\"score\":4,\"reason\":\"stub\"}");
        }
        return "{\"scores\":[" + String.join(",", scores) + "]}";
    }

    @Test
    @DisplayName("LIVE pipeline with stub model: all ENABLED pass, report + calibration written, RECORD round-trips")
    void livePipeline_withStubModel() throws Exception {
        CompanionAiClient originalBase = harness.aiClient().base();
        harness.aiClient().useBase(new GoldenScriptedCompanionAiClient());
        try {
            Path goldenDir = tempDir.resolve("golden");
            LiveEvalExecutor executor = harness.liveExecutor(
                    new EvalScenarioEnvironment.RecordSettings(goldenDir, "stub-model"));
            LiveEvalSuite.Result result = harness.suite(executor::runDetailed).run(new LiveEvalSuite.Options(
                    List.of(), 1, new JudgeService(LiveEvalPipelineIT::fakeJudge), tempDir.resolve("eval-live")));

            EvalSuiteSummary summary = result.summary();
            assertThat(result.cases()).hasSize(19);
            assertThat(summary.overall().hardPassRate())
                    .as("failures: %s", result.cases().stream()
                            .filter(c -> !c.outcome().hardPass())
                            .map(c -> c.outcome().scenarioCode() + "=" + c.outcome().hardResult().failures())
                            .toList())
                    .isEqualTo(1.0);
            assertThat(summary.overall().toolSelectionAccuracy()).isEqualTo(1.0);
            assertThat(summary.overall().judgeRuns()).isEqualTo(19);
            assertThat(summary.overall().judgeThresholdPassRate()).isEqualTo(1.0);
            assertThat(summary.overall().ttftP50()).isNotNull();
            assertThat(summary.overall().avgTokensIn()).isNotNull();
            assertThat(result.cases()).allSatisfy(c -> {
                if (c.outcome().metrics().llmCalls() > 0) {
                    assertThat(c.outcome().metrics().tokensEstimated()).as(c.outcome().scenarioCode()).isFalse();
                }
            });

            Path out = result.outDir();
            assertThat(out.resolve("report.md")).exists();
            assertThat(out.resolve("calibration.csv")).exists();
            EvalSuiteSummary reread = EvalSuiteReportWriter.readJson(out.resolve("report.json"));
            assertThat(reread.scenarios()).hasSize(19);
            assertThat(Files.readString(out.resolve("report.md"))).contains("S01", "S19");

            // RECORD: recorded goldens equal the scripted ones (S07: faults intercept both calls → 0 rounds)
            GoldenLoader loader = new GoldenLoader();
            ObjectMapper mapper = new ObjectMapper();
            for (String code : List.of("S01", "S06", "S07", "S13", "S15")) {
                GoldenFile recorded = loader.load(goldenDir.resolve(code + ".json"));
                GoldenFile source = loader.loadFromClasspath(code);
                assertThat(recorded.isSynthetic()).isFalse();
                assertThat(recorded.model()).isEqualTo("stub-model");
                com.fasterxml.jackson.databind.JsonNode recordedTurns = mapper.readTree(mapper.writeValueAsString(recorded.turns()));
                com.fasterxml.jackson.databind.JsonNode sourceTurns = mapper.readTree(mapper.writeValueAsString(source.turns()));
                assertThat(recordedTurns).as(code).isEqualTo(sourceTurns);
            }
        } finally {
            harness.aiClient().useBase(originalBase);
        }
    }
}
