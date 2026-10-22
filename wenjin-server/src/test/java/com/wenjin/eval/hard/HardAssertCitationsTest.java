package com.wenjin.eval.hard;

import com.wenjin.eval.scenario.EvalScenarioDocument.HardAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** R4: hard key {@code citationsValid} reads the trace's citation_check step. */
class HardAssertCitationsTest {

    private final HardAssertChecker checker = new HardAssertChecker();

    private static HardAssertions hard(Boolean citationsValid) {
        return new HardAssertions(List.of(), List.of(), null, null, null, null, citationsValid);
    }

    private static EvalStepView citation(String output) {
        return new EvalStepView("CITATION", "citation_check", 0, "{\"cited\":[1],\"citable\":[1]}", output);
    }

    private HardAssertResult run(HardAssertions hard, EvalStepView... steps) {
        return checker.check(new HardAssertInput(List.of(steps), "回复 资料 #1", hard, Set.of()));
    }

    @Test
    void passesWhenEveryCitationStepHasNoInvalid() {
        HardAssertResult r = run(hard(true), citation("{\"valid\":[1,2],\"invalid\":[]}"));
        assertThat(r.passed()).as("%s", r.failures()).isTrue();
    }

    @Test
    void failsWhenAnyInvalidId() {
        HardAssertResult r = run(hard(true),
                citation("{\"valid\":[1],\"invalid\":[]}"),
                citation("{\"valid\":[],\"invalid\":[999]}"));
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).extracting(HardAssertFailure::check).contains("citationsValid");
        assertThat(r.failures().get(0).message()).contains("999");
    }

    @Test
    void failsWhenStepOutputIsUnparseable() {
        HardAssertResult r = run(hard(true), citation("not json"));
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).extracting(HardAssertFailure::check).contains("citationsValid");
    }

    @Test
    void noCitationStepMeansNothingWasCitedAndPasses() {
        HardAssertResult r = run(hard(true), new EvalStepView("LLM_CALL", "llm_round", 0, null, "{}"));
        assertThat(r.passed()).as("%s", r.failures()).isTrue();
    }

    @Test
    void notCheckedWhenNullOrFalse() {
        EvalStepView bad = citation("{\"valid\":[],\"invalid\":[999]}");
        assertThat(run(hard(null), bad).passed()).isTrue();
        assertThat(run(hard(false), bad).passed()).isTrue();
    }

    @Test
    void citationStepIsNotCountedAsToolAttempt() {
        HardAssertions capped = new HardAssertions(List.of(), List.of(), 0, null, null, null, true);
        HardAssertResult r = checker.check(new HardAssertInput(
                List.of(citation("{\"valid\":[1],\"invalid\":[]}")), "x", capped, Set.of()));
        assertThat(r.passed()).as("%s", r.failures()).isTrue();
    }
}
