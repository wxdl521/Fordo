package com.wenjin.eval.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.wenjin.eval.hard.EvalStepView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * RECORD exporter: extract {@code LLM_CALL} steps into a golden JSON file.
 * <p>Pure logic + file write; no Spring dependency.
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
     * Build a golden document from agent steps (only {@code LLM_CALL}).
     */
    public GoldenFile build(String scenario, List<EvalStepView> steps) {
        return build(scenario, defaultModel, Instant.now(), steps);
    }

    /**
     * Build with explicit model and recordedAt (stable for unit tests).
     */
    public GoldenFile build(String scenario, String model, Instant recordedAt, List<EvalStepView> steps) {
        Objects.requireNonNull(scenario, "scenario");
        if (scenario.isBlank()) {
            throw new IllegalArgumentException("scenario is required");
        }
        String at = recordedAt == null ? Instant.now().toString() : recordedAt.toString();
        String m = model == null || model.isBlank() ? defaultModel : model;

        List<GoldenLlmCall> calls = new ArrayList<>();
        int idx = 0;
        if (steps != null) {
            for (EvalStepView step : steps) {
                if (step == null || !step.isLlmCall()) {
                    continue;
                }
                calls.add(new GoldenLlmCall(idx++, step.name(), step.outputJson()));
            }
        }
        return new GoldenFile(scenario, at, m, calls);
    }

    /**
     * Serialize golden document to JSON string.
     */
    public String toJson(GoldenFile golden) {
        Objects.requireNonNull(golden, "golden");
        try {
            return mapper.writeValueAsString(golden);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize golden file: " + e.getMessage(), e);
        }
    }

    /**
     * Write golden JSON to {@code path} (parent dirs created as needed).
     */
    public Path write(GoldenFile golden, Path path) {
        Objects.requireNonNull(golden, "golden");
        Objects.requireNonNull(path, "path");
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = toJson(golden);
            Files.writeString(path, json);
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write golden file: " + path + " — " + e.getMessage(), e);
        }
    }

    /**
     * Build + write convenience for RECORD mode after a LIVE run.
     */
    public Path export(String scenario, String model, Instant recordedAt,
                       List<EvalStepView> steps, Path path) {
        return write(build(scenario, model, recordedAt, steps), path);
    }

    /**
     * Default path under a base dir: {@code baseDir/S0X.json}.
     */
    public static Path defaultGoldenPath(Path baseDir, String scenarioCode) {
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(scenarioCode, "scenarioCode");
        return baseDir.resolve(scenarioCode + ".json");
    }
}
