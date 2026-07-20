package com.wenjin.eval.replay;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.golden.GoldenLlmCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayCompanionAiClientTest {

    @Test
    @DisplayName("ordered replay: phase1 tool_calls then phase2 stream")
    void orderedReplay_phase1ThenPhase2() {
        GoldenFile golden = new GoldenFile(
                "S01",
                "2026-07-20T00:00:00Z",
                "deepseek-chat",
                List.of(
                        new GoldenLlmCall(0, "phase1",
                                "{\"type\":\"tool_calls\",\"calls\":[{\"id\":\"c1\",\"name\":\"query_mastery\",\"arguments\":\"{\\\"nodeCode\\\":\\\"SE-3.2\\\"}\"}]}"),
                        new GoldenLlmCall(1, "phase2", "根据掌握度，建议先补用例图。")));

        ReplayCompanionAiClient client = new ReplayCompanionAiClient(golden);

        CompanionAiClient.TurnOutcome outcome = client.completeWithTools(List.of(), List.of());
        assertThat(outcome).isInstanceOf(CompanionAiClient.ToolCalls.class);
        CompanionAiClient.ToolCalls tools = (CompanionAiClient.ToolCalls) outcome;
        assertThat(tools.calls()).hasSize(1);
        assertThat(tools.calls().get(0).name()).isEqualTo("query_mastery");
        assertThat(tools.calls().get(0).argumentsJson()).contains("SE-3.2");

        List<String> tokens = new ArrayList<>();
        client.streamMessages(List.of(), tokens::add);
        assertThat(tokens).containsExactly("根据掌握度，建议先补用例图。");
        assertThat(client.remaining()).isZero();
    }

    @Test
    @DisplayName("phase1 final_text → FinalText; stream path also works")
    void phase1FinalText_andStream() {
        ReplayCompanionAiClient client = new ReplayCompanionAiClient(new GoldenFile(
                "S09", "t", "m",
                List.of(
                        new GoldenLlmCall(0, "phase1", "{\"type\":\"final_text\",\"text\":\"direct\"}"),
                        new GoldenLlmCall(1, "phase2", "streamed answer"))));

        assertThat(client.completeWithTools(List.of(), List.of()))
                .isEqualTo(new CompanionAiClient.FinalText("direct"));

        StringBuilder sb = new StringBuilder();
        client.stream("sys", List.of(), "hi", sb::append);
        assertThat(sb.toString()).isEqualTo("streamed answer");
    }

    @Test
    @DisplayName("exhaust → FAIL with need re-record")
    void exhaust_failsWithNeedReRecord() {
        ReplayCompanionAiClient client = new ReplayCompanionAiClient(new GoldenFile(
                "S01", "t", "m",
                List.of(new GoldenLlmCall(0, "phase1", "{\"type\":\"final_text\",\"text\":\"x\"}"))));

        client.completeWithTools(List.of(), List.of());

        assertThatThrownBy(() -> client.streamMessages(List.of(), t -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ReplayCompanionAiClient.NEED_RE_RECORD)
                .hasMessageContaining("exhausted");
    }

    @Test
    @DisplayName("wrong name → FAIL with need re-record")
    void wrongName_fails() {
        ReplayCompanionAiClient client = new ReplayCompanionAiClient(new GoldenFile(
                "S01", "t", "m",
                List.of(
                        new GoldenLlmCall(0, "phase2", "oops this is phase2"),
                        new GoldenLlmCall(1, "phase1", "{\"type\":\"final_text\",\"text\":\"x\"}"))));

        assertThatThrownBy(() -> client.completeWithTools(List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ReplayCompanionAiClient.NEED_RE_RECORD)
                .hasMessageContaining("name mismatch")
                .hasMessageContaining("expected=phase1")
                .hasMessageContaining("actual=phase2");
    }

    @Test
    @DisplayName("degraded names-only phase1 still yields ToolCalls")
    void degradedNamesOnly() {
        ReplayCompanionAiClient client = new ReplayCompanionAiClient("S", List.of(
                new GoldenLlmCall(0, "phase1",
                        "{\"type\":\"tool_calls\",\"names\":[\"query_mastery\",\"get_node_detail\"]}")));

        CompanionAiClient.TurnOutcome outcome = client.completeWithTools(List.of(), List.of());
        assertThat(outcome).isInstanceOf(CompanionAiClient.ToolCalls.class);
        CompanionAiClient.ToolCalls tools = (CompanionAiClient.ToolCalls) outcome;
        assertThat(tools.calls()).extracting(CompanionAiClient.ToolCall::name)
                .containsExactly("query_mastery", "get_node_detail");
        assertThat(tools.calls()).extracting(CompanionAiClient.ToolCall::argumentsJson)
                .containsExactly("{}", "{}");
    }

    @Test
    @DisplayName("parsePhase1: OpenAI-shaped body")
    void parseOpenAiShape() {
        String body = """
                {"choices":[{"message":{"tool_calls":[{"id":"call_1","function":{"name":"start_practice","arguments":"{\\"nodeCode\\":\\"KT01\\"}"}}]}}]}
                """;
        CompanionAiClient.TurnOutcome outcome = ReplayCompanionAiClient.parsePhase1(body);
        assertThat(outcome).isInstanceOf(CompanionAiClient.ToolCalls.class);
        CompanionAiClient.ToolCalls tools = (CompanionAiClient.ToolCalls) outcome;
        assertThat(tools.calls().get(0).name()).isEqualTo("start_practice");
        assertThat(tools.calls().get(0).argumentsJson()).contains("KT01");
    }

    @Test
    @DisplayName("plain text phase1 → FinalText")
    void plainTextFinal() {
        assertThat(ReplayCompanionAiClient.parsePhase1("hello"))
                .isEqualTo(new CompanionAiClient.FinalText("hello"));
    }
}
