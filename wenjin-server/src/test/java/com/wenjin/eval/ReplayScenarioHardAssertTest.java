package com.wenjin.eval;

import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.golden.GoldenHardAssertSupport;
import com.wenjin.eval.golden.GoldenHardAssertSupport.ExpandedRun;
import com.wenjin.eval.golden.GoldenLoader;
import com.wenjin.eval.golden.GoldenLlmCall;
import com.wenjin.eval.hard.HardAssertChecker;
import com.wenjin.eval.hard.HardAssertInput;
import com.wenjin.eval.hard.HardAssertResult;
import com.wenjin.eval.replay.ReplayCompanionAiClient;
import com.wenjin.eval.report.EvalReportCase;
import com.wenjin.eval.report.EvalReportGenerator;
import com.wenjin.eval.runner.EvalCaseOutcome;
import com.wenjin.eval.runner.EvalMode;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.EvalScenarioStatus;
import com.wenjin.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI-safe REPLAY hard-assert suite (no Spring / Docker / AI keys).
 * <p>For each ENABLED scenario: load classpath golden → expand to steps →
 * {@link HardAssertChecker} must pass. Also smoke-loads {@link ReplayCompanionAiClient}.
 * <p>Writes {@code target/eval-report.md} summary after the parameterized run.
 * Full LIVE+RECORD refresh is documented for when AI keys are available.
 */
class ReplayScenarioHardAssertTest {

    private static final ScenarioLoader SCENARIO_LOADER = new ScenarioLoader();
    private static final GoldenLoader GOLDEN_LOADER = new GoldenLoader();
    private static final HardAssertChecker CHECKER = new HardAssertChecker();
    private static final List<EvalReportCase> REPORT_CASES = new CopyOnWriteArrayList<>();

    static Stream<String> enabledScenarioCodes() {
        return SCENARIO_LOADER.loadAll().stream()
                .filter(d -> d.resolvedStatus() == EvalScenarioStatus.ENABLED)
                .map(EvalScenarioDocument::code)
                .sorted();
    }

    @ParameterizedTest(name = "REPLAY hard-assert {0}")
    @MethodSource("enabledScenarioCodes")
    @DisplayName("ENABLED golden → expanded steps → HardAssertChecker PASS")
    void enabledGolden_hardAssertPasses(String code) {
        EvalScenarioDocument scenario = SCENARIO_LOADER.loadByCode(code)
                .orElseThrow(() -> new AssertionError("missing scenario " + code));
        assertThat(scenario.resolvedStatus()).isEqualTo(EvalScenarioStatus.ENABLED);

        GoldenFile golden = GOLDEN_LOADER.loadFromClasspath(code);
        assertThat(golden.scenario()).isEqualTo(code);
        assertThat(golden.llmCalls()).isNotEmpty();

        ExpandedRun expanded = GoldenHardAssertSupport.expand(golden);
        assertThat(expanded.finalResponse()).isNotBlank();

        HardAssertResult hard = CHECKER.check(new HardAssertInput(
                expanded.steps(),
                expanded.finalResponse(),
                scenario.hard(),
                GoldenHardAssertSupport.whitelistFor(scenario)));

        assertThat(hard.passed())
                .as("%s failures=%s", code, hard.failures())
                .isTrue();

        // Companion golden is consumable by ReplayCompanionAiClient (phase1/phase2 order)
        ReplayCompanionAiClient client = new ReplayCompanionAiClient(golden);
        for (GoldenLlmCall call : golden.llmCalls()) {
            if ("phase1".equals(call.name())) {
                client.completeWithTools(List.of(), List.of());
            } else if ("phase2".equals(call.name())) {
                StringBuilder sb = new StringBuilder();
                client.streamMessages(List.of(), sb::append);
                assertThat(sb.toString()).isEqualTo(call.output());
            }
        }
        assertThat(client.remaining()).isZero();

        List<String> tools = expanded.steps().stream()
                .filter(s -> s.isToolCall())
                .map(s -> s.name())
                .distinct()
                .toList();
        REPORT_CASES.add(EvalReportCase.of(EvalCaseOutcome.completed(
                code,
                EvalMode.REPLAY,
                hard,
                null,
                null,
                expanded.finalResponse(),
                tools)));
    }

    @Test
    @DisplayName("intentionally broken hard assert fails (proves checker is not always-green)")
    void intentionallyBrokenAssertion_fails() {
        EvalScenarioDocument s01 = SCENARIO_LOADER.loadByCode("S01").orElseThrow();
        GoldenFile golden = GOLDEN_LOADER.loadFromClasspath("S01");
        ExpandedRun expanded = GoldenHardAssertSupport.expand(golden);

        // Strip all TOOL_CALL steps → mustCallTools query_mastery must fail
        var noTools = expanded.steps().stream()
                .filter(s -> !s.isToolCall())
                .toList();
        HardAssertResult hard = CHECKER.check(new HardAssertInput(
                noTools,
                expanded.finalResponse(),
                s01.hard(),
                GoldenHardAssertSupport.whitelistFor(s01)));
        assertThat(hard.passed()).isFalse();
        assertThat(hard.failures()).anyMatch(f -> "mustCallTools".equals(f.check()));
    }

    @AfterAll
    static void writeEvalReport() {
        // Sort for stable report; include empty summary if no cases (should not happen)
        List<EvalReportCase> cases = new ArrayList<>(REPORT_CASES);
        cases.sort((a, b) -> a.outcome().scenarioCode().compareTo(b.outcome().scenarioCode()));
        Path path = new EvalReportGenerator().writeDefault(cases);
        assertThat(path).exists();
    }
}
