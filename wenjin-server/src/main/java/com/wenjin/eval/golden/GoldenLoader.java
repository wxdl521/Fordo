package com.wenjin.eval.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Load golden JSON (format v2) from path or classpath for REPLAY.
 */
public final class GoldenLoader {

    public static final String DEFAULT_CLASSPATH_PREFIX = "eval/golden/";
    public static final String NEED_RE_RECORD = "need re-record";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectMapper mapper;

    public GoldenLoader() {
        this(MAPPER);
    }

    public GoldenLoader(ObjectMapper mapper) {
        this.mapper = mapper == null ? MAPPER : mapper;
    }

    public GoldenFile load(Path path) {
        Objects.requireNonNull(path, "path");
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in, path.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read golden file: " + path, e);
        }
    }

    /**
     * Load {@code classpath:eval/golden/{scenarioCode}.json}.
     */
    public GoldenFile loadFromClasspath(String scenarioCode) {
        Objects.requireNonNull(scenarioCode, "scenarioCode");
        String location = DEFAULT_CLASSPATH_PREFIX + scenarioCode + ".json";
        Resource resource = new ClassPathResource(location);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Golden not found on classpath: " + location
                    + " — need re-record");
        }
        try (InputStream in = resource.getInputStream()) {
            return parse(in, location);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read golden classpath resource: " + location, e);
        }
    }

    public GoldenFile parse(InputStream in, String sourceName) {
        Objects.requireNonNull(in, "in");
        String where = sourceName != null ? " (" + sourceName + ")" : "";
        JsonNode root;
        try {
            root = mapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid golden JSON" + where + ": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Invalid golden JSON" + where + ": not an object");
        }
        if (root.has("llmCalls") || root.path("format").asInt(0) != GoldenFile.FORMAT_V2) {
            throw new IllegalArgumentException("Unsupported golden format" + where
                    + ": expected \"format\":2 with turns/rounds (v1 phase1/phase2 llmCalls is obsolete) — "
                    + NEED_RE_RECORD);
        }
        GoldenFile file;
        try {
            file = mapper.treeToValue(root, GoldenFile.class);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid golden JSON" + where + ": " + e.getMessage(), e);
        }
        if (file.scenario() == null || file.scenario().isBlank()) {
            throw new IllegalArgumentException("Golden missing scenario" + where);
        }
        for (int t = 0; t < file.turns().size(); t++) {
            List<GoldenFile.GoldenRound> rounds = file.turns().get(t).rounds();
            for (int r = 0; r < rounds.size(); r++) {
                for (GoldenFile.GoldenToolCall call : rounds.get(r).toolCalls()) {
                    if (call.name().isBlank()) {
                        throw new IllegalArgumentException("Golden" + where + " turns[" + t + "].rounds[" + r
                                + "] has a tool call without name");
                    }
                }
            }
        }
        return file;
    }
}
