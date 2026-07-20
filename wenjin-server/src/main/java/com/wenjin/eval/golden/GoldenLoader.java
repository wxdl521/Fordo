package com.wenjin.eval.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Load golden JSON from path or classpath for REPLAY.
 */
public final class GoldenLoader {

    public static final String DEFAULT_CLASSPATH_PREFIX = "eval/golden/";

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
        try {
            GoldenFile file = mapper.readValue(in, GoldenFile.class);
            if (file.scenario() == null || file.scenario().isBlank()) {
                throw new IllegalArgumentException(
                        "Golden missing scenario" + (sourceName != null ? " (" + sourceName + ")" : ""));
            }
            return file;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Invalid golden JSON" + (sourceName != null ? " (" + sourceName + ")" : "")
                            + ": " + e.getMessage(),
                    e);
        }
    }
}
