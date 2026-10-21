package com.wenjin.eval.golden;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

/**
 * Golden LLM replay document, format v2 ({@code eval/golden/S0X.json}).
 *
 * <pre>
 * {"scenario":"S0X","format":2,"recordedAt":"...","model":"deepseek-chat","synthetic":false,
 *  "turns":[{"rounds":[{"content":"","toolCalls":[{"name":"query_mastery","arguments":{"nodeCode":"SE-3.2"}}],
 *                       "finishReason":"tool_calls"},
 *                      {"content":"...","toolCalls":[],"finishReason":"stop"}]}]}
 * </pre>
 * One turn per scenario script line; one round per {@code CompanionAiClient#streamTurn} call
 * (legacy {@code stream} fallbacks also consume one round). {@code synthetic=true} marks hand-written
 * goldens that were not recorded from a real model.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"scenario", "format", "recordedAt", "model", "synthetic", "turns"})
public record GoldenFile(
        String scenario,
        Integer format,
        String recordedAt,
        String model,
        Boolean synthetic,
        List<GoldenTurn> turns
) {
    public static final int FORMAT_V2 = 2;

    public GoldenFile {
        Objects.requireNonNull(scenario, "scenario");
        format = format == null ? FORMAT_V2 : format;
        recordedAt = recordedAt == null ? "" : recordedAt;
        model = model == null ? "" : model;
        synthetic = synthetic != null && synthetic;
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public boolean isSynthetic() {
        return Boolean.TRUE.equals(synthetic);
    }

    public int roundCount() {
        return turns.stream().mapToInt(t -> t.rounds().size()).sum();
    }

    /** One scripted user turn: the LLM rounds the orchestrator consumed while answering it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoldenTurn(List<GoldenRound> rounds) {
        public GoldenTurn {
            rounds = rounds == null ? List.of() : List.copyOf(rounds);
        }
    }

    /** One streamed LLM response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"content", "toolCalls", "finishReason"})
    public record GoldenRound(String content, List<GoldenToolCall> toolCalls, String finishReason) {
        public GoldenRound {
            content = content == null ? "" : content;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            if (finishReason == null || finishReason.isBlank()) {
                finishReason = toolCalls.isEmpty() ? "stop" : "tool_calls";
            }
        }
    }

    /**
     * A tool call requested by the model. {@code arguments} is kept as JSON text and written raw (so the
     * golden shows a JSON object); the loader accepts either a JSON object or a JSON-encoded string.
     */
    @JsonPropertyOrder({"name", "arguments"})
    public record GoldenToolCall(String name, @JsonRawValue String arguments) {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        public GoldenToolCall {
            name = name == null ? "" : name;
            arguments = normalizeArguments(arguments);
        }

        @JsonCreator
        static GoldenToolCall fromJson(@JsonProperty("name") String name,
                                       @JsonProperty("arguments") JsonNode arguments) {
            if (arguments == null || arguments.isNull() || arguments.isMissingNode()) {
                return new GoldenToolCall(name, "{}");
            }
            return new GoldenToolCall(name, arguments.isTextual() ? arguments.asText() : arguments.toString());
        }

        /** Valid JSON is kept verbatim; anything else is preserved as a JSON string literal. */
        static String normalizeArguments(String raw) {
            if (raw == null || raw.isBlank()) {
                return "{}";
            }
            try {
                MAPPER.readTree(raw);
                return raw;
            } catch (Exception e) {
                try {
                    return MAPPER.writeValueAsString(raw);
                } catch (Exception impossible) {
                    return "{}";
                }
            }
        }
    }
}
