package com.wenjin.eval.judge;

import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JudgeVerdictAndClientTest {

    private static final List<JudgeDimension> DIMS = List.of(
            new JudgeDimension("a", "", 4),
            new JudgeDimension("b", "", null));

    @Test
    void verdictPassesWhenAllAtOrAboveThreshold() {
        JudgeResult r = JudgeResult.ok("v", List.of(new JudgeScore("a", 4, ""), new JudgeScore("b", 1, "")), 1);
        JudgeVerdict v = JudgeVerdict.evaluate(r, DIMS);
        assertThat(v.passed()).isTrue();
    }

    @Test
    void verdictFailsBelowThreshold() {
        JudgeResult r = JudgeResult.ok("v", List.of(new JudgeScore("a", 3, ""), new JudgeScore("b", 5, "")), 1);
        JudgeVerdict v = JudgeVerdict.evaluate(r, DIMS);
        assertThat(v.status()).isEqualTo(JudgeVerdict.Status.FAIL);
        assertThat(v.failedKeys()).containsExactly("a");
    }

    @Test
    void missingJudgeIsNaAndContractFailureIsFail() {
        assertThat(JudgeVerdict.evaluate(null, DIMS).notApplicable()).isTrue();
        assertThat(JudgeVerdict.evaluate(JudgeResult.failure("v", "x", 3), DIMS).status())
                .isEqualTo(JudgeVerdict.Status.FAIL);
    }

    @Test
    void requestBodyIsJsonObjectTemperatureZero() {
        Map<String, Object> body = OpenAiCompatibleJudgeAiClient.buildRequestBody("m1", "hello");
        assertThat(body.get("model")).isEqualTo("m1");
        assertThat(body.get("temperature")).isEqualTo(0);
        assertThat(body.get("response_format")).isEqualTo(Map.of("type", "json_object"));
        assertThat(body.get("messages").toString()).contains("hello");
    }

    @Test
    void modelFallbackAndUrl() {
        assertThat(OpenAiCompatibleJudgeAiClient.resolveModel("", "chat")).isEqualTo("chat");
        assertThat(OpenAiCompatibleJudgeAiClient.resolveModel(null, "chat")).isEqualTo("chat");
        assertThat(OpenAiCompatibleJudgeAiClient.resolveModel(" judge ", "chat")).isEqualTo("judge");
        assertThat(OpenAiCompatibleJudgeAiClient.chatCompletionsUrl("https://x.com/"))
                .isEqualTo("https://x.com/v1/chat/completions");
    }

    @Test
    void parsesContentAndRejectsMalformed() {
        assertThat(OpenAiCompatibleJudgeAiClient.parseContent(
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"scores\\\":[]}\"}}]}")).isEqualTo("{\"scores\":[]}");
        assertThatThrownBy(() -> OpenAiCompatibleJudgeAiClient.parseContent("{\"choices\":[]}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OpenAiCompatibleJudgeAiClient.parseContent("not json"))
                .isInstanceOf(IllegalStateException.class);
    }
}
