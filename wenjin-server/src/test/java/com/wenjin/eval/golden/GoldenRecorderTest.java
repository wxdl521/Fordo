package com.wenjin.eval.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.ai.CompanionAiClient.StreamTurnResult;
import com.wenjin.ai.CompanionAiClient.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoldenRecorderTest {

    private final GoldenRecorder recorder = new GoldenRecorder();
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private static List<List<StreamTurnResult>> sampleTurns() {
        return List.of(
                List.of(
                        new StreamTurnResult("", List.of(new ToolCall("c1", "query_mastery", "{\"nodeCode\":\"SE-3.2\"}")),
                                new CompanionAiClient.Usage(100, 5, null), "tool_calls"),
                        new StreamTurnResult("先看薄弱点。", List.of(), null, "stop")),
                List.of(new StreamTurnResult("第二轮", List.of(), null, "stop")));
    }

    @Test
    @DisplayName("recorded turns → v2 JSON: format 2, turns/rounds, arguments written as a JSON object")
    void build_v2Structure() throws Exception {
        GoldenFile golden = recorder.build("S01", "deepseek-chat",
                Instant.parse("2026-10-21T10:00:00Z"), false, sampleTurns());

        JsonNode root = mapper.readTree(recorder.toJson(golden));
        assertThat(root.path("format").asInt()).isEqualTo(2);
        assertThat(root.path("synthetic").asBoolean()).isFalse();
        assertThat(root.path("model").asText()).isEqualTo("deepseek-chat");
        assertThat(root.path("recordedAt").asText()).isEqualTo("2026-10-21T10:00:00Z");
        JsonNode r0 = root.path("turns").get(0).path("rounds").get(0);
        assertThat(r0.path("toolCalls").get(0).path("name").asText()).isEqualTo("query_mastery");
        assertThat(r0.path("toolCalls").get(0).path("arguments").isObject()).isTrue();
        assertThat(r0.path("toolCalls").get(0).path("arguments").path("nodeCode").asText()).isEqualTo("SE-3.2");
        assertThat(r0.path("finishReason").asText()).isEqualTo("tool_calls");
        assertThat(root.path("turns").get(1).path("rounds").get(0).path("content").asText()).isEqualTo("第二轮");
    }

    @Test
    @DisplayName("write → load round-trips")
    void writeAndLoad_roundTrip() {
        GoldenFile golden = recorder.build("S01", "m", Instant.parse("2026-10-21T10:00:00Z"), true, sampleTurns());
        Path out = recorder.write(golden, GoldenRecorder.defaultGoldenPath(tempDir, "S01"));

        GoldenFile loaded = new GoldenLoader().load(out);
        assertThat(loaded).isEqualTo(golden);
        assertThat(loaded.isSynthetic()).isTrue();
        assertThat(loaded.roundCount()).isEqualTo(3);
        assertThat(loaded.turns().get(0).rounds().get(0).toolCalls().get(0).arguments())
                .isEqualTo("{\"nodeCode\":\"SE-3.2\"}");
    }

    @Test
    @DisplayName("loader accepts string-encoded arguments; rejects v1 llmCalls goldens with need re-record")
    void loader_stringArgs_andV1Rejected() {
        String v2 = """
                {"scenario":"S09","format":2,"turns":[{"rounds":[
                  {"content":"","toolCalls":[{"name":"get_learning_frontier","arguments":"{\\"limit\\":3}"}]},
                  {"content":"hi"}]}]}
                """;
        GoldenFile parsed = new GoldenLoader().parse(
                new ByteArrayInputStream(v2.getBytes(StandardCharsets.UTF_8)), "inline");
        assertThat(parsed.turns().get(0).rounds().get(0).toolCalls().get(0).arguments()).isEqualTo("{\"limit\":3}");
        assertThat(parsed.turns().get(0).rounds().get(1).finishReason()).isEqualTo("stop");

        String v1 = """
                {"scenario":"S09","llmCalls":[{"idx":0,"name":"phase1","output":"x"}]}
                """;
        assertThatThrownBy(() -> new GoldenLoader().parse(
                new ByteArrayInputStream(v1.getBytes(StandardCharsets.UTF_8)), "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("need re-record");
    }

    @Test
    @DisplayName("RecordingCompanionAiClient captures streamTurn results per turn and passes deltas through")
    void recordingClient_capturesPerTurn() {
        List<StreamTurnResult> script = new ArrayList<>(List.of(
                new StreamTurnResult("ab", List.of(), null, "stop"),
                new StreamTurnResult("", List.of(new ToolCall("x", "get_review_queue", "{}")), null, "tool_calls")));
        CompanionAiClient stub = new StubClient(script);
        RecordingCompanionAiClient rec = new RecordingCompanionAiClient(stub);

        rec.beginTurn();
        StringBuilder seen = new StringBuilder();
        rec.streamTurn(List.of(), List.of(), CompanionAiClient.ToolChoice.AUTO, seen::append);
        rec.beginTurn();
        rec.streamTurn(List.of(), List.of(), CompanionAiClient.ToolChoice.AUTO, seen::append);

        assertThat(seen.toString()).isEqualTo("ab");
        assertThat(rec.recordedTurns()).hasSize(2);
        assertThat(rec.recordedTurns().get(1).get(0).toolCalls().get(0).name()).isEqualTo("get_review_queue");
    }

    /** Pops scripted results; streams content as one delta. */
    static final class StubClient implements CompanionAiClient {
        private final List<StreamTurnResult> script;

        StubClient(List<StreamTurnResult> script) {
            this.script = script;
        }

        @Override
        public StreamTurnResult streamTurn(List<java.util.Map<String, Object>> messages,
                                           List<java.util.Map<String, Object>> toolSchemas,
                                           ToolChoice toolChoice, java.util.function.Consumer<String> onContentDelta) {
            StreamTurnResult r = script.remove(0);
            if (!r.content().isEmpty()) {
                onContentDelta.accept(r.content());
            }
            return r;
        }

        @Override
        public void stream(String s, List<ChatMsg> h, String u, java.util.function.Consumer<String> t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TurnOutcome completeWithTools(List<java.util.Map<String, Object>> m, List<java.util.Map<String, Object>> s) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void streamMessages(List<java.util.Map<String, Object>> m, java.util.function.Consumer<String> t) {
            throw new UnsupportedOperationException();
        }
    }
}
