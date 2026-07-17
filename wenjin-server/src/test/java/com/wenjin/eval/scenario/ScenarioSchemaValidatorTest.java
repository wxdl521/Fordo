package com.wenjin.eval.scenario;

import com.wenjin.eval.scenario.EvalScenarioDocument.HardAssertions;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeSpec;
import com.wenjin.eval.scenario.EvalScenarioDocument.Persona;
import com.wenjin.eval.scenario.EvalScenarioDocument.ScriptTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioSchemaValidatorTest {

    @Test
    @DisplayName("合法最小场景通过校验")
    void validMinimalDocument_passes() {
        EvalScenarioDocument doc = validBase("S01", "ok");
        assertThatCode(() -> ScenarioSchemaValidator.validate(doc)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("缺少 code 失败")
    void missingCode_fails() {
        EvalScenarioDocument doc = new EvalScenarioDocument(
                null, "t", emptyPersona(), List.of(new ScriptTurn("hi")),
                emptyHard(), oneDimJudge(), null, null);
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(doc))
                .isInstanceOf(ScenarioSchemaException.class)
                .hasMessageContaining("code");
    }

    @Test
    @DisplayName("code 格式非法失败")
    void badCodePattern_fails() {
        EvalScenarioDocument doc = validBase("scenario-1", "t");
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(doc))
                .isInstanceOf(ScenarioSchemaException.class)
                .hasMessageContaining("code must match");
    }

    @Test
    @DisplayName("空 script 失败")
    void emptyScript_fails() {
        EvalScenarioDocument doc = new EvalScenarioDocument(
                "S01", "t", emptyPersona(), List.of(),
                emptyHard(), oneDimJudge(), null, null);
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(doc))
                .isInstanceOf(ScenarioSchemaException.class)
                .hasMessageContaining("script");
    }

    @Test
    @DisplayName("缺少 hard 失败")
    void missingHard_fails() {
        EvalScenarioDocument doc = new EvalScenarioDocument(
                "S01", "t", emptyPersona(), List.of(new ScriptTurn("hi")),
                null, oneDimJudge(), null, null);
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(doc))
                .isInstanceOf(ScenarioSchemaException.class)
                .hasMessageContaining("hard");
    }

    @Test
    @DisplayName("judge 维度阈值越界失败")
    void judgeThresholdOutOfRange_fails() {
        JudgeSpec judge = new JudgeSpec(List.of(
                new JudgeDimension("expression", "表达", 9)));
        EvalScenarioDocument doc = new EvalScenarioDocument(
                "S01", "t", emptyPersona(), List.of(new ScriptTurn("hi")),
                emptyHard(), judge, null, null);
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(doc))
                .isInstanceOf(ScenarioSchemaException.class)
                .hasMessageContaining("threshold");
    }

    @Test
    @DisplayName("PENDING 无 pendingReason 失败")
    void pendingWithoutReason_fails() {
        EvalScenarioDocument doc = new EvalScenarioDocument(
                "S07", "t", emptyPersona(), List.of(new ScriptTurn("hi")),
                emptyHard(), oneDimJudge(), "PENDING", null);
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(doc))
                .isInstanceOf(ScenarioSchemaException.class)
                .hasMessageContaining("pendingReason");
    }

    @Test
    @DisplayName("PENDING 带 pendingReason 通过")
    void pendingWithReason_passes() {
        EvalScenarioDocument doc = new EvalScenarioDocument(
                "S07", "t", emptyPersona(), List.of(new ScriptTurn("hi")),
                emptyHard(), oneDimJudge(), "PENDING", "M9-reflection");
        assertThatCode(() -> ScenarioSchemaValidator.validate(doc)).doesNotThrowAnyException();
        assertThatCode(() -> doc.resolvedStatus())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("重复 judge key 失败")
    void duplicateJudgeKey_fails() {
        JudgeSpec judge = new JudgeSpec(List.of(
                new JudgeDimension("expression", "a", 3),
                new JudgeDimension("expression", "b", 3)));
        EvalScenarioDocument doc = new EvalScenarioDocument(
                "S01", "t", emptyPersona(), List.of(new ScriptTurn("hi")),
                emptyHard(), judge, null, null);
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(doc))
                .isInstanceOf(ScenarioSchemaException.class)
                .hasMessageContaining("duplicated");
    }

    private static EvalScenarioDocument validBase(String code, String title) {
        return new EvalScenarioDocument(
                code, title, emptyPersona(), List.of(new ScriptTurn("hi")),
                emptyHard(), oneDimJudge(), "ENABLED", null);
    }

    private static Persona emptyPersona() {
        return new Persona(List.of(), List.of(), "note");
    }

    private static HardAssertions emptyHard() {
        return new HardAssertions(List.of(), List.of(), 4, true, true, false);
    }

    private static JudgeSpec oneDimJudge() {
        return new JudgeSpec(List.of(new JudgeDimension("expression", "表达质量与语气", 3)));
    }
}
