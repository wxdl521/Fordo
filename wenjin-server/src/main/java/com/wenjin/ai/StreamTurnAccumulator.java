package com.wenjin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.CompanionAiClient.StreamTurnResult;
import com.wenjin.ai.CompanionAiClient.ToolCall;
import com.wenjin.ai.CompanionAiClient.Usage;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Line-by-line SSE accumulator for one streamed chat-completions turn. Network-free, so it is
 * unit-testable. Content deltas are forwarded immediately; tool_call deltas are merged by index.
 * reasoning_content is ignored. Callback failures surface as {@link SinkFailureException}.
 */
final class StreamTurnAccumulator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Consumer<String> onContentDelta;
    private final StringBuilder content = new StringBuilder();
    private final TreeMap<Integer, PartialCall> calls = new TreeMap<>();
    private String finishReason;
    private Usage usage;

    StreamTurnAccumulator(Consumer<String> onContentDelta) {
        this.onContentDelta = onContentDelta;
    }

    private static final class PartialCall {
        String id = "";
        String name = "";
        final StringBuilder args = new StringBuilder();
    }

    void accept(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("data:")) {
            return;
        }
        String data = trimmed.substring(5).trim();
        if (data.isEmpty() || data.equals("[DONE]")) {
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(data);
        } catch (Exception e) {
            return;
        }
        if (root == null || !root.isObject()) {
            return;
        }

        JsonNode usageNode = root.path("usage");
        if (usageNode.isObject()) {
            usage = parseUsage(usageNode);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");
        if (delta.isObject()) {
            JsonNode c = delta.path("content");
            if (c.isTextual() && !c.asText().isEmpty()) {
                String text = c.asText();
                content.append(text);
                forward(text);
            }
            JsonNode tcs = delta.path("tool_calls");
            if (tcs.isArray()) {
                for (JsonNode tc : tcs) {
                    mergeToolCall(tc);
                }
            }
        }
        JsonNode fr = choice.path("finish_reason");
        if (fr.isTextual() && !fr.asText().isEmpty()) {
            finishReason = fr.asText();
        }
    }

    private void forward(String text) {
        try {
            onContentDelta.accept(text);
        } catch (SinkFailureException sfe) {
            throw sfe;
        } catch (RuntimeException e) {
            throw new SinkFailureException(e);
        }
    }

    private void mergeToolCall(JsonNode tc) {
        int index = tc.path("index").asInt(calls.size());
        PartialCall pc = calls.computeIfAbsent(index, k -> new PartialCall());
        JsonNode id = tc.path("id");
        if (id.isTextual() && pc.id.isEmpty()) {
            pc.id = id.asText();
        }
        JsonNode fn = tc.path("function");
        JsonNode name = fn.path("name");
        if (name.isTextual() && pc.name.isEmpty()) {
            pc.name = name.asText();
        }
        JsonNode args = fn.path("arguments");
        if (args.isTextual()) {
            pc.args.append(args.asText());
        }
    }

    private static Usage parseUsage(JsonNode u) {
        int prompt = u.path("prompt_tokens").asInt(0);
        int completion = u.path("completion_tokens").asInt(0);
        Integer cached = null;
        if (u.path("prompt_cache_hit_tokens").isNumber()) {
            cached = u.path("prompt_cache_hit_tokens").asInt();
        } else if (u.path("prompt_tokens_details").path("cached_tokens").isNumber()) {
            cached = u.path("prompt_tokens_details").path("cached_tokens").asInt();
        }
        return new Usage(prompt, completion, cached);
    }

    StreamTurnResult result() {
        List<ToolCall> out = new ArrayList<>();
        for (PartialCall pc : calls.values()) {
            if (pc.name.isBlank()) {
                continue;
            }
            String args = pc.args.length() == 0 ? "{}" : pc.args.toString();
            out.add(new ToolCall(pc.id, pc.name, args));
        }
        return new StreamTurnResult(content.toString(), List.copyOf(out), usage, finishReason);
    }
}
