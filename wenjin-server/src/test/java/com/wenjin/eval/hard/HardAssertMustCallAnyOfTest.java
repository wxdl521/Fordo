package com.wenjin.eval.hard;

import com.wenjin.eval.metrics.EvalSuiteAggregator.ToolExpectation;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.EvalScenarioDocument.HardAssertions;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeSpec;
import com.wenjin.eval.scenario.EvalScenarioDocument.Persona;
import com.wenjin.eval.scenario.EvalScenarioDocument.ScriptTurn;
import com.wenjin.eval.scenario.ScenarioLoader;
import com.wenjin.eval.scenario.ScenarioSchemaException;
import com.wenjin.eval.scenario.ScenarioSchemaValidator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hard key {@code mustCallAnyOf}: each group needs at least one successfully called tool. */
class HardAssertMustCallAnyOfTest {

    private final HardAssertChecker checker = new HardAssertChecker();

    private static HardAssertions hard(List<List<String>> anyOf, List<String> replayOnly) {
        return new HardAssertions(null, null, null, null, null, null, null, null, null, null, replayOnly, anyOf);
    }

    private static EvalStepView tool(String name) {
        return new EvalStepView("TOOL_CALL", name, 0, "{}", "{}");
    }

    private HardAssertResult run(HardAssertions hard, EvalStepView... steps) {
        return checker.check(new HardAssertInput(List.of(steps), "reply", hard, Set.of()));
    }

    private static EvalScenarioDocument doc(HardAssertions hard) {
        return new EvalScenarioDocument("T09", "t", new Persona(List.of(), List.of(), "n"),
                List.of(new ScriptTurn("hi")), hard,
                new JudgeSpec(List.of(new JudgeDimension("expression", "x", 3))), "ENABLED", null, null, null);
    }

    @Test
    void passesWhenAnyToolOfGroupWasCalled() {
        HardAssertions h = hard(List.of(List.of("query_mastery", "trace_prerequisites")), null);
        assertThat(run(h, tool("trace_prerequisites")).passed()).isTrue();
        assertThat(run(h, tool("query_mastery"), tool("get_node_detail")).passed()).isTrue();
    }

    @Test
    void failsWhenNoToolOfGroupWasCalledOrOnlyGuarded() {
        HardAssertions h = hard(List.of(List.of("query_mastery", "trace_prerequisites")), null);
        HardAssertResult r = run(h, tool("get_node_detail"), new EvalStepView("GUARD", "query_mastery", 1, "{}", "{}"));
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).extracting(HardAssertFailure::check).containsExactly("mustCallAnyOf");
    }

    @Test
    void allGroupsMustBeSatisfied() {
        HardAssertions h = hard(List.of(List.of("a", "b"), List.of("c", "d")), null);
        assertThat(run(h, tool("a"), tool("d")).passed()).isTrue();
        assertThat(run(h, tool("a"), tool("b")).passed()).isFalse();
    }

    @Test
    void validatorRejectsEmptyAndBlankGroups() {
        assertThatCode(() -> ScenarioSchemaValidator.validate(doc(hard(List.of(List.of("a", "b")), null))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(doc(hard(List.of(), null))))
                .isInstanceOf(ScenarioSchemaException.class).hasMessageContaining("mustCallAnyOf");
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(doc(hard(List.of(List.of()), null))))
                .isInstanceOf(ScenarioSchemaException.class).hasMessageContaining("mustCallAnyOf[0]");
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(doc(hard(List.of(List.of("a", " ")), null))))
                .isInstanceOf(ScenarioSchemaException.class).hasMessageContaining("mustCallAnyOf[0][1]");
    }

    @Test
    void replayOnlyMayNameMustCallAnyOfAndLiveViewDropsIt() {
        HardAssertions h = hard(List.of(List.of("a", "b")), List.of("mustCallAnyOf"));
        assertThatCode(() -> ScenarioSchemaValidator.validate(doc(h))).doesNotThrowAnyException();
        assertThat(h.effective(true).mustCallAnyOf()).isNull();
        assertThat(h.effective(false).mustCallAnyOf()).hasSize(1);
        // replayOnly 引用未设置的 mustCallAnyOf 仍被拒
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(doc(hard(null, List.of("mustCallAnyOf")))))
                .isInstanceOf(ScenarioSchemaException.class).hasMessageContaining("not set");
    }

    @Test
    void toolExpectationHonoursGroups() {
        HardAssertions h = hard(List.of(List.of("a", "b")), null);
        ToolExpectation te = ToolExpectation.fromScenario(doc(h), false);
        assertThat(te.mustCallAnyOf()).hasSize(1);
    }

    @Test
    void s01UsesMustCallAnyOfForMastery() throws Exception {
        String path = "eval/scenarios/S01.json";
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            EvalScenarioDocument s01 = new ScenarioLoader().parseAndValidate(in, path);
            assertThat(s01.hard().mustCallTools()).isNullOrEmpty();
            assertThat(s01.hard().mustCallAnyOf())
                    .containsExactly(List.of("query_mastery", "trace_prerequisites"));
        }
    }
}
