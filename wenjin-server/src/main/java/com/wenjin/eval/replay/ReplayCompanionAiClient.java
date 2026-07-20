package com.wenjin.eval.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.golden.GoldenLlmCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * REPLAY {@link CompanionAiClient}: consumes golden {@code llmCalls} in order.
 * <ul>
 *   <li>{@link #completeWithTools} → next call named {@code phase1} (or sequential),
 *       parse output as tool decision JSON or {@link FinalText}</li>
 *   <li>{@link #stream} / {@link #streamMessages} → next {@code phase2} output as stream tokens</li>
 * </ul>
 * Exhaustion or type/name mismatch → {@link IllegalStateException} with "need re-record".
 */
public final class ReplayCompanionAiClient implements CompanionAiClient {

    public static final String NEED_RE_RECORD = "need re-record";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String scenario;
    private final List<GoldenLlmCall> calls;
    private final AtomicInteger cursor = new AtomicInteger(0);
    private volatile String lastPhase1Raw;

    public ReplayCompanionAiClient(GoldenFile golden) {
        Objects.requireNonNull(golden, "golden");
        this.scenario = golden.scenario();
        this.calls = List.copyOf(golden.llmCalls());
    }

    public ReplayCompanionAiClient(String scenario, List<GoldenLlmCall> calls) {
        this.scenario = scenario == null ? "?" : scenario;
        this.calls = calls == null ? List.of() : List.copyOf(calls);
    }

    public int remaining() {
        return Math.max(0, calls.size() - cursor.get());
    }

    public int consumed() {
        return cursor.get();
    }

    @Override
    public TurnOutcome completeWithTools(List<Map<String, Object>> messages,
                                         List<Map<String, Object>> toolSchemas) {
        GoldenLlmCall call = next("phase1");
        lastPhase1Raw = call.output();
        return parsePhase1(call.output());
    }

    @Override
    public String lastPhase1Raw() {
        return lastPhase1Raw;
    }

    @Override
    public void stream(String systemPrompt, List<ChatMsg> history, String userMessage,
                       Consumer<String> onToken) {
        emitPhase2(onToken);
    }

    @Override
    public void streamMessages(List<Map<String, Object>> messages, Consumer<String> onToken) {
        emitPhase2(onToken);
    }

    private void emitPhase2(Consumer<String> onToken) {
        GoldenLlmCall call = next("phase2");
        String output = call.output();
        if (onToken != null && output != null && !output.isEmpty()) {
            // Whole recorded text as one token is enough for deterministic REPLAY.
            onToken.accept(output);
        }
    }

    private GoldenLlmCall next(String expectedName) {
        int i = cursor.getAndIncrement();
        if (i >= calls.size()) {
            throw exhausted(expectedName, i);
        }
        GoldenLlmCall call = calls.get(i);
        String actual = call.name() == null ? "" : call.name();
        if (!expectedName.equals(actual)) {
            // Sequential fallback: if name is blank, accept; otherwise fail name mismatch.
            if (!actual.isBlank()) {
                throw nameMismatch(expectedName, actual, i);
            }
        }
        return call;
    }

    /** Public for M9 {@code Phase1DecisionParser} and unit tests. */
    public static TurnOutcome parsePhase1(String output) {
        if (output == null || output.isBlank()) {
            return new FinalText("");
        }
        String trimmed = output.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return new FinalText(output);
        }
        try {
            JsonNode root = MAPPER.readTree(trimmed);

            // Compact golden format
            String type = root.path("type").asText("");
            if ("final_text".equals(type) || "final".equals(type)) {
                return new FinalText(root.path("text").asText(""));
            }
            if ("tool_calls".equals(type)) {
                List<ToolCall> toolCalls = parseToolCallsNode(root);
                if (!toolCalls.isEmpty()) {
                    return new ToolCalls(toolCalls);
                }
                return new FinalText("");
            }

            // OpenAI chat.completion shape
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                JsonNode toolCallsNode = message.path("tool_calls");
                if (toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                    List<ToolCall> toolCalls = new ArrayList<>();
                    for (JsonNode callNode : toolCallsNode) {
                        JsonNode functionNode = callNode.path("function");
                        String name = functionNode.path("name").asText("");
                        if (name.isBlank()) {
                            continue;
                        }
                        String args = argumentsAsJson(functionNode.path("arguments"));
                        toolCalls.add(new ToolCall(callNode.path("id").asText(""), name, args));
                    }
                    if (!toolCalls.isEmpty()) {
                        return new ToolCalls(toolCalls);
                    }
                }
                JsonNode content = message.path("content");
                if (!content.isMissingNode() && !content.isNull()) {
                    return new FinalText(content.asText(""));
                }
            }

            // Bare tool_calls array without type
            if (root.has("calls") && root.path("calls").isArray()) {
                List<ToolCall> toolCalls = parseToolCallsNode(root);
                if (!toolCalls.isEmpty()) {
                    return new ToolCalls(toolCalls);
                }
            }

            // Unknown JSON object → treat as final text of the raw string
            return new FinalText(output);
        } catch (Exception e) {
            return new FinalText(output);
        }
    }

    private static List<ToolCall> parseToolCallsNode(JsonNode root) {
        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode callsNode = root.path("calls");
        if (callsNode.isArray() && !callsNode.isEmpty()) {
            for (JsonNode c : callsNode) {
                String name = c.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                String id = c.path("id").asText("call_" + toolCalls.size());
                String args = argumentsAsJson(c.path("arguments"));
                if (args.isEmpty() && c.has("argumentsJson")) {
                    args = argumentsAsJson(c.path("argumentsJson"));
                }
                if (args.isEmpty()) {
                    args = "{}";
                }
                toolCalls.add(new ToolCall(id, name, args));
            }
            return toolCalls;
        }
        // Degraded phase1 trace: {"type":"tool_calls","names":["query_mastery"]}
        JsonNode names = root.path("names");
        if (names.isArray()) {
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i).asText("");
                if (!name.isBlank()) {
                    toolCalls.add(new ToolCall("call_" + i, name, "{}"));
                }
            }
        }
        return toolCalls;
    }

    private static String argumentsAsJson(JsonNode argumentsNode) {
        if (argumentsNode == null || argumentsNode.isMissingNode() || argumentsNode.isNull()) {
            return "";
        }
        if (argumentsNode.isTextual()) {
            return argumentsNode.asText();
        }
        return argumentsNode.toString();
    }

    private IllegalStateException exhausted(String expectedName, int idx) {
        return new IllegalStateException(
                "Golden llmCalls exhausted for scenario=" + scenario
                        + " expected=" + expectedName
                        + " at idx=" + idx
                        + " — " + NEED_RE_RECORD);
    }

    private IllegalStateException nameMismatch(String expected, String actual, int idx) {
        return new IllegalStateException(
                "Golden llmCall name mismatch for scenario=" + scenario
                        + " at idx=" + idx
                        + " expected=" + expected
                        + " actual=" + actual
                        + " — " + NEED_RE_RECORD);
    }
}
