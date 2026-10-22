package com.wenjin.eval.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.eval.golden.GoldenFile.GoldenRound;
import com.wenjin.eval.golden.GoldenFile.GoldenToolCall;
import com.wenjin.eval.golden.GoldenFile.GoldenTurn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * RECORD exporter: real {@code streamTurn} results (captured by {@link RecordingCompanionAiClient})
 * → golden v2 JSON. Pure logic + file write; no Spring dependency.
 */
public final class GoldenRecorder {

    public static final String DEFAULT_MODEL = "deepseek-chat";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final ObjectMapper mapper;
    private final String defaultModel;

    public GoldenRecorder() {
        this(MAPPER, DEFAULT_MODEL);
    }

    public GoldenRecorder(ObjectMapper mapper, String defaultModel) {
        this.mapper = mapper == null ? MAPPER : mapper;
        this.defaultModel = defaultModel == null || defaultModel.isBlank()
                ? DEFAULT_MODEL
                : defaultModel;
    }

    /**
     * Build a v2 golden from recorded turns (outer list = scenario turns, inner = LLM rounds).
     */
    public GoldenFile build(String scenario,
                            String model,
                            Instant recordedAt,
                            boolean synthetic,
                            List<List<CompanionAiClient.StreamTurnResult>> turns) {
        Objects.requireNonNull(scenario, "scenario");
        if (scenario.isBlank()) {
            throw new IllegalArgumentException("scenario is required");
        }
        String at = (recordedAt == null ? Instant.now() : recordedAt).toString();
        String m = model == null || model.isBlank() ? defaultModel : model;
        List<GoldenTurn> goldenTurns = new ArrayList<>();
        if (turns != null) {
            for (List<CompanionAiClient.StreamTurnResult> rounds : turns) {
                List<GoldenRound> goldenRounds = new ArrayList<>();
                if (rounds != null) {
                    for (CompanionAiClient.StreamTurnResult r : rounds) {
                        if (r != null) {
                            goldenRounds.add(toRound(r));
                        }
                    }
                }
                goldenTurns.add(new GoldenTurn(goldenRounds));
            }
        }
        return new GoldenFile(scenario, GoldenFile.FORMAT_V2, at, m, synthetic, goldenTurns);
    }

    static GoldenRound toRound(CompanionAiClient.StreamTurnResult r) {
        List<GoldenToolCall> calls = new ArrayList<>();
        if (r.toolCalls() != null) {
            for (CompanionAiClient.ToolCall c : r.toolCalls()) {
                if (c != null && c.name() != null && !c.name().isBlank()) {
                    calls.add(new GoldenToolCall(c.name(), c.argumentsJson()));
                }
            }
        }
        return new GoldenRound(r.content(), calls, r.finishReason());
    }

    public String toJson(GoldenFile golden) {
        Objects.requireNonNull(golden, "golden");
        try {
            return mapper.writeValueAsString(golden) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize golden file: " + e.getMessage(), e);
        }
    }

    /** Write golden JSON (UTF-8) to {@code path}; parent dirs are created as needed. */
    public Path write(GoldenFile golden, Path path) {
        Objects.requireNonNull(golden, "golden");
        Objects.requireNonNull(path, "path");
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, toJson(golden), StandardCharsets.UTF_8);
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write golden file: " + path + " — " + e.getMessage(), e);
        }
    }

    /** Default path under a base dir: {@code baseDir/S0X.json}. */
    public static Path defaultGoldenPath(Path baseDir, String scenarioCode) {
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(scenarioCode, "scenarioCode");
        return baseDir.resolve(scenarioCode + ".json");
    }
}
