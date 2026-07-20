package com.wenjin.eval.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.eval.hard.EvalStepView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenRecorderTest {

    private final GoldenRecorder recorder = new GoldenRecorder();
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("steps → golden JSON structure (LLM_CALL only, idx order)")
    void build_filtersLlmCallsAndStructure() throws Exception {
        Instant at = Instant.parse("2026-07-20T10:00:00Z");
        List<EvalStepView> steps = List.of(
                new EvalStepView("LLM_CALL", "phase1", 0, "{}",
                        "{\"type\":\"tool_calls\",\"calls\":[{\"id\":\"c1\",\"name\":\"query_mastery\",\"arguments\":\"{}\"}]}"),
                new EvalStepView("TOOL_CALL", "query_mastery", 0, "{}", "{\"ok\":true}"),
                new EvalStepView("LLM_CALL", "phase2", 0, null, "你好，先看薄弱点。"),
                new EvalStepView("GUARD", "budget", 1, null, null));

        GoldenFile golden = recorder.build("S09", "deepseek-chat", at, steps);

        assertThat(golden.scenario()).isEqualTo("S09");
        assertThat(golden.model()).isEqualTo("deepseek-chat");
        assertThat(golden.recordedAt()).isEqualTo("2026-07-20T10:00:00Z");
        assertThat(golden.llmCalls()).hasSize(2);
        assertThat(golden.llmCalls().get(0).idx()).isEqualTo(0);
        assertThat(golden.llmCalls().get(0).name()).isEqualTo("phase1");
        assertThat(golden.llmCalls().get(0).output()).contains("query_mastery");
        assertThat(golden.llmCalls().get(1).idx()).isEqualTo(1);
        assertThat(golden.llmCalls().get(1).name()).isEqualTo("phase2");
        assertThat(golden.llmCalls().get(1).output()).isEqualTo("你好，先看薄弱点。");

        String json = recorder.toJson(golden);
        JsonNode root = mapper.readTree(json);
        assertThat(root.path("scenario").asText()).isEqualTo("S09");
        assertThat(root.path("recordedAt").asText()).isEqualTo("2026-07-20T10:00:00Z");
        assertThat(root.path("model").asText()).isEqualTo("deepseek-chat");
        assertThat(root.path("llmCalls").isArray()).isTrue();
        assertThat(root.path("llmCalls")).hasSize(2);
        assertThat(root.path("llmCalls").get(0).path("idx").asInt()).isEqualTo(0);
        assertThat(root.path("llmCalls").get(0).path("name").asText()).isEqualTo("phase1");
        assertThat(root.path("llmCalls").get(1).path("name").asText()).isEqualTo("phase2");
    }

    @Test
    @DisplayName("export writes file under temp dir; loader round-trips")
    void export_writeAndLoad() {
        Instant at = Instant.parse("2026-07-20T12:00:00Z");
        List<EvalStepView> steps = List.of(
                new EvalStepView("LLM_CALL", "phase1", 0, null, "{\"type\":\"final_text\",\"text\":\"ok\"}"),
                new EvalStepView("LLM_CALL", "phase2", 0, null, "ok reply"));

        Path out = GoldenRecorder.defaultGoldenPath(tempDir, "S01");
        recorder.export("S01", "deepseek-chat", at, steps, out);

        assertThat(Files.exists(out)).isTrue();

        GoldenFile loaded = new GoldenLoader().load(out);
        assertThat(loaded.scenario()).isEqualTo("S01");
        assertThat(loaded.model()).isEqualTo("deepseek-chat");
        assertThat(loaded.llmCalls()).hasSize(2);
        assertThat(loaded.llmCalls().get(0).name()).isEqualTo("phase1");
        assertThat(loaded.llmCalls().get(1).output()).isEqualTo("ok reply");
    }

    @Test
    @DisplayName("empty steps → llmCalls empty array")
    void build_emptySteps() {
        GoldenFile golden = recorder.build("S02", "m", Instant.parse("2026-01-01T00:00:00Z"), List.of());
        assertThat(golden.llmCalls()).isEmpty();
        assertThat(recorder.toJson(golden)).contains("\"llmCalls\"");
    }
}
