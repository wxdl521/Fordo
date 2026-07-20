package com.wenjin.eval.judge;

import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JudgeServiceTest {

    private static final List<JudgeDimension> DIMS = List.of(
            new JudgeDimension("symptom_fit", "对症", 3),
            new JudgeDimension("guidance", "引导", 3));

    @Test
    @DisplayName("parse OK: valid JSON → scores ordered by dimensions")
    void parseOk() {
        JudgeService service = new JudgeService(prompt -> {
            throw new AssertionError("not used");
        });

        List<JudgeScore> scores = service.parseAndValidate("""
                {"scores":[
                  {"key":"guidance","score":4,"reason":"guided well"},
                  {"key":"symptom_fit","score":5,"reason":"on point"}
                ]}
                """, DIMS);

        assertThat(scores).extracting(JudgeScore::key).containsExactly("symptom_fit", "guidance");
        assertThat(scores).extracting(JudgeScore::score).containsExactly(5, 4);
        assertThat(scores.get(0).reason()).isEqualTo("on point");
    }

    @Test
    @DisplayName("retry on invalid then success")
    void retryThenSuccess() {
        AtomicInteger calls = new AtomicInteger();
        JudgeAiClient client = prompt -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return "not json";
            }
            return """
                    {"scores":[
                      {"key":"symptom_fit","score":3,"reason":"ok"},
                      {"key":"guidance","score":4,"reason":"ok"}
                    ]}
                    """;
        };
        JudgeService service = new JudgeService(client);
        JudgeResult result = service.judge(new JudgeRequest("t", "tools", "persona", DIMS));

        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.promptVersion()).isEqualTo(JudgeService.PROMPT_VERSION);
        assertThat(result.scores()).hasSize(2);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("failure after retries (≤2) → success=false judge failure")
    void failureAfterRetries() {
        AtomicInteger calls = new AtomicInteger();
        JudgeAiClient client = prompt -> {
            calls.incrementAndGet();
            return "{\"scores\":[{\"key\":\"only_one\",\"score\":3,\"reason\":\"x\"}]}";
        };
        JudgeService service = new JudgeService(client);
        JudgeResult result = service.judge(new JudgeRequest("t", "tools", "note", DIMS));

        assertThat(result.success()).isFalse();
        assertThat(result.attempts()).isEqualTo(1 + JudgeService.MAX_RETRIES);
        assertThat(calls.get()).isEqualTo(1 + JudgeService.MAX_RETRIES);
        assertThat(result.failureReason()).contains("missing dimensions");
        assertThat(result.promptVersion()).isEqualTo("judge-prompt-v1");
        assertThat(result.scores()).isEmpty();
    }

    @Test
    @DisplayName("version string recorded on success and failure")
    void versionStringRecorded() {
        JudgeService service = new JudgeService(
                p -> "{\"scores\":[{\"key\":\"a\",\"score\":1,\"reason\":\"r\"}]}",
                "custom template {{dimensions}} {{transcript}} {{tool_summary}} {{persona_note}}",
                "judge-prompt-v1",
                0);

        JudgeResult ok = service.judge(new JudgeRequest("x", "y", "z",
                List.of(new JudgeDimension("a", "d", 3))));
        assertThat(ok.success()).isTrue();
        assertThat(ok.promptVersion()).isEqualTo("judge-prompt-v1");
        assertThat(service.promptVersion()).isEqualTo("judge-prompt-v1");

        JudgeService failing = new JudgeService(p -> "bad", "t {{dimensions}}", "judge-prompt-v1", 0);
        JudgeResult fail = failing.judge(new JudgeRequest("x", "y", "z",
                List.of(new JudgeDimension("a", "d", 3))));
        assertThat(fail.success()).isFalse();
        assertThat(fail.promptVersion()).isEqualTo("judge-prompt-v1");
    }

    @Test
    @DisplayName("score out of 1–5 rejected")
    void scoreOutOfRange() {
        JudgeService service = new JudgeService(p -> "");
        assertThatThrownBy(() -> service.parseAndValidate(
                "{\"scores\":[{\"key\":\"symptom_fit\",\"score\":6,\"reason\":\"x\"},"
                        + "{\"key\":\"guidance\",\"score\":3,\"reason\":\"y\"}]}",
                DIMS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    @DisplayName("renderPrompt substitutes placeholders and loads v1 resource")
    void renderPrompt_usesTemplate() {
        JudgeService service = new JudgeService(p -> "");
        String prompt = service.renderPrompt(new JudgeRequest(
                "hello transcript",
                "query_mastery ok",
                "weak on SE-3.2",
                DIMS));
        assertThat(prompt).contains("hello transcript");
        assertThat(prompt).contains("query_mastery ok");
        assertThat(prompt).contains("weak on SE-3.2");
        assertThat(prompt).contains("symptom_fit");
        assertThat(prompt).contains("guidance");
    }
}
