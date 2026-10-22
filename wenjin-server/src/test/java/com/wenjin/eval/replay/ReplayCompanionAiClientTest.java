package com.wenjin.eval.replay;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.golden.GoldenFile.GoldenRound;
import com.wenjin.eval.golden.GoldenFile.GoldenToolCall;
import com.wenjin.eval.golden.GoldenFile.GoldenTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayCompanionAiClientTest {

    private static GoldenFile golden(GoldenTurn... turns) {
        return new GoldenFile("S01", 2, "t", "m", true, List.of(turns));
    }

    private static GoldenRound tools(GoldenToolCall... calls) {
        return new GoldenRound("", List.of(calls), null);
    }

    private static GoldenRound text(String content) {
        return new GoldenRound(content, List.of(), null);
    }

    @Test
    @DisplayName("streamTurn replays rounds in order: tool calls, then streamed content in chunks")
    void streamTurn_replaysRoundsInOrder() {
        ReplayCompanionAiClient client = new ReplayCompanionAiClient(golden(new GoldenTurn(List.of(
                tools(new GoldenToolCall("query_mastery", "{\"nodeCode\":\"SE-3.2\"}")),
                text("根据掌握度，建议先补用例图。")))));
        client.beginTurn();

        List<String> deltas = new ArrayList<>();
        CompanionAiClient.StreamTurnResult r0 = client.streamTurn(List.of(), List.of(),
                CompanionAiClient.ToolChoice.AUTO, deltas::add);
        assertThat(r0.toolCalls()).extracting(CompanionAiClient.ToolCall::name).containsExactly("query_mastery");
        assertThat(r0.toolCalls().get(0).argumentsJson()).isEqualTo("{\"nodeCode\":\"SE-3.2\"}");
        assertThat(r0.toolCalls().get(0).id()).isNotBlank();
        assertThat(r0.finishReason()).isEqualTo("tool_calls");
        assertThat(deltas).isEmpty();

        CompanionAiClient.StreamTurnResult r1 = client.streamTurn(List.of(), List.of(),
                CompanionAiClient.ToolChoice.AUTO, deltas::add);
        assertThat(r1.toolCalls()).isEmpty();
        assertThat(r1.content()).isEqualTo("根据掌握度，建议先补用例图。");
        assertThat(r1.finishReason()).isEqualTo("stop");
        assertThat(deltas).hasSizeBetween(2, ReplayCompanionAiClient.MAX_CHUNKS);
        assertThat(String.join("", deltas)).isEqualTo("根据掌握度，建议先补用例图。");

        assertThat(client.remaining()).isZero();
        assertThat(client.verifyFullyConsumed()).isEmpty();
    }

    @Test
    @DisplayName("beginTurn advances to the next turn; legacy stream consumes one round")
    void beginTurn_advances_andLegacyStreamConsumesRound() {
        ReplayCompanionAiClient client = new ReplayCompanionAiClient(golden(
                new GoldenTurn(List.of(text("第一轮"))),
                new GoldenTurn(List.of(text("legacy 回退")))));
        client.beginTurn();
        client.streamTurn(List.of(), List.of(), CompanionAiClient.ToolChoice.AUTO, t -> {});
        client.beginTurn();
        StringBuilder sb = new StringBuilder();
        client.stream("sys", List.of(), "hi", sb::append);
        assertThat(sb.toString()).isEqualTo("legacy 回退");
        assertThat(client.consumed()).isEqualTo(2);
        assertThat(client.verifyFullyConsumed()).isEmpty();
    }

    @Test
    @DisplayName("exhaustion throws with 'need re-record' and is reported as a problem")
    void exhaustion_failsWithNeedReRecord() {
        ReplayCompanionAiClient client = new ReplayCompanionAiClient(golden(new GoldenTurn(List.of(text("x")))));
        client.beginTurn();
        client.streamTurn(List.of(), List.of(), CompanionAiClient.ToolChoice.AUTO, t -> {});

        assertThatThrownBy(() -> client.streamTurn(List.of(), List.of(), CompanionAiClient.ToolChoice.NONE, t -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ReplayCompanionAiClient.NEED_RE_RECORD)
                .hasMessageContaining("exhausted");
        assertThat(client.problems()).singleElement().asString().contains("exhausted");
    }

    @Test
    @DisplayName("unconsumed rounds and unreached turns are reported (orchestration drifted)")
    void unconsumedRounds_reported() {
        ReplayCompanionAiClient client = new ReplayCompanionAiClient(golden(
                new GoldenTurn(List.of(tools(new GoldenToolCall("query_mastery", "{}")), text("done"))),
                new GoldenTurn(List.of(text("second")))));
        client.beginTurn();
        client.streamTurn(List.of(), List.of(), CompanionAiClient.ToolChoice.AUTO, t -> {});
        client.beginTurn(); // turn 0 still has 1 round left
        List<String> problems = client.verifyFullyConsumed();
        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).contains("turn 0").contains("not consumed").contains("need re-record");
        assertThat(problems.get(1)).contains("turn 1").contains("not consumed");
    }

    @Test
    @DisplayName("first call without beginTurn implicitly starts turn 0; completeWithTools maps rounds")
    void implicitFirstTurn_andCompleteWithTools() {
        ReplayCompanionAiClient client = new ReplayCompanionAiClient(golden(new GoldenTurn(List.of(
                tools(new GoldenToolCall("get_node_detail", "{}")), text("direct")))));
        assertThat(client.completeWithTools(List.of(), List.of())).isInstanceOf(CompanionAiClient.ToolCalls.class);
        assertThat(client.completeWithTools(List.of(), List.of()))
                .isEqualTo(new CompanionAiClient.FinalText("direct"));
    }

    @Test
    @DisplayName("chunks split on code points, never more than MAX_CHUNKS")
    void chunks_codePointSafe() {
        assertThat(ReplayCompanionAiClient.chunks("")).isEmpty();
        assertThat(ReplayCompanionAiClient.chunks("ab")).containsExactly("a", "b");
        String emoji = "😀😀😀😀";
        assertThat(String.join("", ReplayCompanionAiClient.chunks(emoji))).isEqualTo(emoji);
        assertThat(ReplayCompanionAiClient.chunks("abcdefghij")).hasSize(3);
    }

    @Test
    @DisplayName("parsePhase1 (kept for Phase1DecisionParser): compact and OpenAI shapes")
    void parsePhase1_shapes() {
        assertThat(ReplayCompanionAiClient.parsePhase1("hello"))
                .isEqualTo(new CompanionAiClient.FinalText("hello"));
        String body = """
                {"choices":[{"message":{"tool_calls":[{"id":"call_1","function":{"name":"start_practice","arguments":"{\\"nodeCode\\":\\"KT01\\"}"}}]}}]}
                """;
        CompanionAiClient.TurnOutcome outcome = ReplayCompanionAiClient.parsePhase1(body);
        assertThat(outcome).isInstanceOf(CompanionAiClient.ToolCalls.class);
        assertThat(((CompanionAiClient.ToolCalls) outcome).calls().get(0).argumentsJson()).contains("KT01");
        assertThat(ReplayCompanionAiClient.parsePhase1("{\"type\":\"tool_calls\",\"names\":[\"query_mastery\"]}"))
                .isInstanceOf(CompanionAiClient.ToolCalls.class);
    }
}
