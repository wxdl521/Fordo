package com.wenjin.eval.golden;

import com.wenjin.eval.hard.EvalStepView;
import com.wenjin.eval.hard.HardAssertChecker;
import com.wenjin.eval.hard.HardAssertInput;
import com.wenjin.eval.hard.HardAssertResult;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.EvalScenarioStatus;
import com.wenjin.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds ENABLED scenario goldens via {@link GoldenRecorder} and verifies they
 * satisfy hard asserts (full synthetic steps).
 * <p>To refresh committed files under {@code src/test/resources/eval/golden/}:
 * {@code -Dwenjin.eval.write-goldens=true}.
 * <p>Full LIVE+RECORD refresh is for when AI keys are available; these fixtures
 * are the CI-safe deterministic baseline.
 */
class GenerateEnabledGoldensTest {

    private final GoldenRecorder recorder = new GoldenRecorder();
    private final ScenarioLoader scenarioLoader = new ScenarioLoader();
    private final HardAssertChecker checker = new HardAssertChecker();

    @Test
    @DisplayName("ENABLED synthetic steps pass hard asserts; goldens via GoldenRecorder")
    void exportAndVerifyEnabledGoldens() throws Exception {
        Instant at = Instant.parse(EnabledScenarioStepsFactory.RECORDED_AT);
        Path resourcesGolden = Path.of("src/test/resources/eval/golden");
        boolean write = "true".equalsIgnoreCase(System.getProperty("wenjin.eval.write-goldens", "false"));

        List<EvalScenarioDocument> enabled = scenarioLoader.loadAll().stream()
                .filter(d -> d.resolvedStatus() == EvalScenarioStatus.ENABLED)
                .toList();

        Set<String> enabledCodes = enabled.stream().map(EvalScenarioDocument::code).collect(Collectors.toSet());
        assertThat(enabledCodes)
                .as("factory must cover every ENABLED scenario")
                .isEqualTo(EnabledScenarioStepsFactory.enabledCodes());

        if (write) {
            Files.createDirectories(resourcesGolden);
        }

        for (EvalScenarioDocument scenario : enabled) {
            String code = scenario.code();
            List<EvalStepView> steps = EnabledScenarioStepsFactory.stepsFor(code);

            // Full synthetic steps (incl. TOOL_CALL) must pass hard asserts
            String finalResponse = lastPhase2(steps);
            HardAssertResult hard = checker.check(new HardAssertInput(
                    steps,
                    finalResponse,
                    scenario.hard(),
                    GoldenHardAssertSupport.whitelistFor(scenario)));
            assertThat(hard.passed())
                    .as("%s full steps failures=%s", code, hard.failures())
                    .isTrue();

            GoldenFile golden = recorder.build(code, EnabledScenarioStepsFactory.MODEL, at, steps);
            assertThat(golden.llmCalls()).isNotEmpty();
            assertThat(golden.scenario()).isEqualTo(code);

            Path out = GoldenRecorder.defaultGoldenPath(resourcesGolden, code);
            if (write) {
                recorder.export(code, EnabledScenarioStepsFactory.MODEL, at, steps, out);
            }

            // Committed file must exist and match GoldenRecorder serialization
            assertThat(out)
                    .as("golden file for %s — run with -Dwenjin.eval.write-goldens=true if missing", code)
                    .exists();
            GoldenFile loaded = new GoldenLoader().load(out);
            assertThat(loaded.scenario()).isEqualTo(code);
            assertThat(loaded.model()).isEqualTo(EnabledScenarioStepsFactory.MODEL);
            assertThat(loaded.llmCalls()).hasSize(golden.llmCalls().size());
            for (int i = 0; i < golden.llmCalls().size(); i++) {
                GoldenLlmCall expected = golden.llmCalls().get(i);
                GoldenLlmCall actual = loaded.llmCalls().get(i);
                assertThat(actual.idx()).isEqualTo(expected.idx());
                assertThat(actual.name()).isEqualTo(expected.name());
                assertThat(actual.output()).isEqualTo(expected.output());
            }
        }
    }

    private static String lastPhase2(List<EvalStepView> steps) {
        String text = "";
        for (EvalStepView s : steps) {
            if (s.isLlmCall() && "phase2".equals(s.name()) && s.outputJson() != null) {
                text = s.outputJson();
            }
        }
        return text;
    }
}
