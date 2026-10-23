package com.wenjin.eval.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.golden.GoldenFile.GoldenRound;
import com.wenjin.eval.golden.GoldenFile.GoldenToolCall;
import com.wenjin.eval.runner.TurnAware;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REPLAY {@link CompanionAiClient} over a golden v2 file: every LLM call consumes the next recorded
 * round of the current scenario turn, in order.
 * <ul>
 *   <li>{@link #streamTurn}: content is delivered through {@code onContentDelta} in a few chunks (exercising
 *       the orchestrator's streaming path), tool calls are returned as recorded;</li>
 *   <li>legacy {@link #stream}/{@link #streamMessages} fallbacks consume one round and stream its content;</li>
 *   <li>{@link #beginTurn()} moves to the next scenario turn (called by the eval turn runner);</li>
 *   <li>golden text may use {@code {{cite:<source title>}}} / {@code {{cite:N}}} to cite a chunk returned by {@code search_materials}
 *       (see {@link #resolveCitePlaceholders}).</li>
 * </ul>
 * Running out of rounds throws {@link IllegalStateException} mentioning {@value #NEED_RE_RECORD}; rounds left
 * unconsumed when a turn ends are reported by {@link #problems()} / {@link #verifyFullyConsumed()} — both
 * mean the orchestration no longer matches the recording.
 */
public final class ReplayCompanionAiClient implements CompanionAiClient, TurnAware {

    public static final String NEED_RE_RECORD = "need re-record";

    /** Content is split into at most this many deltas. */
    static final int MAX_CHUNKS = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CITE_PLACEHOLDER = Pattern.compile("\\{\\{cite:([^}]+)\\}\\}");

    private final String scenario;
    private final List<GoldenFile.GoldenTurn> turns;
    private final List<String> problems = new ArrayList<>();
    private int turnIdx = -1;
    private int roundIdx;
    private int consumed;

    public ReplayCompanionAiClient(GoldenFile golden) {
        Objects.requireNonNull(golden, "golden");
        this.scenario = golden.scenario();
        this.turns = golden.turns();
    }

    @Override
    public synchronized void beginTurn() {
        if (turnIdx >= 0) {
            reportUnconsumed(turnIdx);
        }
        turnIdx++;
        roundIdx = 0;
    }

    /** Rounds left in the current and all following turns. */
    public synchronized int remaining() {
        int left = 0;
        for (int t = Math.max(0, turnIdx); t < turns.size(); t++) {
            int size = turns.get(t).rounds().size();
            left += t == turnIdx ? Math.max(0, size - roundIdx) : size;
        }
        return left;
    }

    public synchronized int consumed() {
        return consumed;
    }

    /** Mismatches observed so far (exhaustion, unconsumed rounds). */
    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    /**
     * Call after the last scenario turn: reports unconsumed rounds of the current turn and turns that were
     * never started. Returns all problems.
     */
    public synchronized List<String> verifyFullyConsumed() {
        if (turnIdx >= 0) {
            reportUnconsumed(turnIdx);
        }
        for (int t = Math.max(0, turnIdx + 1); t < turns.size(); t++) {
            if (!turns.get(t).rounds().isEmpty()) {
                problems.add("scenario=" + scenario + " turn " + t + " was never reached ("
                        + turns.get(t).rounds().size() + " recorded rounds) — " + NEED_RE_RECORD);
            }
        }
        // idempotent: mark everything consumed
        turnIdx = turns.size();
        roundIdx = 0;
        return List.copyOf(problems);
    }

    @Override
    public StreamTurnResult streamTurn(List<Map<String, Object>> messages,
                                       List<Map<String, Object>> toolSchemas,
                                       ToolChoice toolChoice,
                                       Consumer<String> onContentDelta) {
        Next next = next("streamTurn");
        String content = resolveCitePlaceholders(next.round().content(), messages);
        emit(content, onContentDelta);
        return new StreamTurnResult(content, toToolCalls(next), null, next.round().finishReason());
    }

    @Override
    public void stream(String systemPrompt, List<ChatMsg> history, String userMessage,
                       Consumer<String> onToken) {
        emit(next("stream").round().content(), onToken);
    }

    @Override
    public void streamMessages(List<Map<String, Object>> messages, Consumer<String> onToken) {
        emit(resolveCitePlaceholders(next("streamMessages").round().content(), messages), onToken);
    }

    @Override
    public TurnOutcome completeWithTools(List<Map<String, Object>> messages,
                                         List<Map<String, Object>> toolSchemas) {
        Next next = next("completeWithTools");
        List<ToolCall> calls = toToolCalls(next);
        return calls.isEmpty() ? new FinalText(next.round().content()) : new ToolCalls(calls);
    }

    /**
     * Golden text may reference a chunk id the recorded run cannot know (ids are DB-assigned):
     * <ul>
     *   <li>{@code {{cite:<title>}}} - the chunk whose {@code sourceTitle} equals {@code <title>} among the
     *       {@code search_materials} results already present in {@code messages} (ranking-independent);</li>
     *   <li>{@code {{cite:N}}} - the N-th (1-based) distinct chunk of those results.</li>
     * </ul>
     * An unresolvable placeholder becomes {@code 0} (never a real chunk id), so the reply then fails the
     * {@code citationsValid} hard assert instead of passing silently.
     */
    static String resolveCitePlaceholders(String content, List<Map<String, Object>> messages) {
        if (content == null || !content.contains("{{cite:")) {
            return content;
        }
        List<ToolChunk> chunks = toolResultChunks(messages);
        Matcher m = CITE_PLACEHOLDER.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).trim();
            String id = "0";
            if (key.matches("\\d+")) {
                int n = key.length() > 6 ? 0 : Integer.parseInt(key);
                if (n >= 1 && n <= chunks.size()) {
                    id = String.valueOf(chunks.get(n - 1).id());
                }
            } else {
                for (ToolChunk chunk : chunks) {
                    if (key.equals(chunk.title())) {
                        id = String.valueOf(chunk.id());
                        break;
                    }
                }
                if ("0".equals(id)) {
                    // 真实录制的 golden 常引用 RETRIEVAL 直接注入的资料块（不经 search_materials）
                    for (ToolChunk chunk : injectedChunks(messages)) {
                        if (key.equals(chunk.title())) {
                            id = String.valueOf(chunk.id());
                            break;
                        }
                    }
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(id));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private record ToolChunk(long id, String title) {
    }

    /** RETRIEVAL 注入块标签：{@code 【资料 #<chunkId> · <title>】}（见 RetrievalService.formatInjectionBlocks）。 */
    private static final Pattern INJECTED_LABEL = Pattern.compile("【资料 #(\\d{1,18}) · ([^】\\n]+)】");

    private static List<ToolChunk> injectedChunks(List<Map<String, Object>> messages) {
        Map<Long, ToolChunk> chunks = new LinkedHashMap<>();
        if (messages != null) {
            for (Map<String, Object> message : messages) {
                if (message == null || !(message.get("content") instanceof String body)) {
                    continue;
                }
                Matcher m = INJECTED_LABEL.matcher(body);
                while (m.find()) {
                    long id = Long.parseLong(m.group(1));
                    chunks.putIfAbsent(id, new ToolChunk(id, m.group(2).trim()));
                }
            }
        }
        return new ArrayList<>(chunks.values());
    }

    private static List<ToolChunk> toolResultChunks(List<Map<String, Object>> messages) {
        Map<Long, ToolChunk> chunks = new LinkedHashMap<>();
        if (messages != null) {
            for (Map<String, Object> message : messages) {
                if (message == null || !"tool".equals(message.get("role"))
                        || !(message.get("content") instanceof String body)) {
                    continue;
                }
                try {
                    for (JsonNode item : MAPPER.readTree(body).path("items")) {
                        if (item.path("chunkId").isIntegralNumber()) {
                            long id = item.path("chunkId").asLong();
                            chunks.putIfAbsent(id, new ToolChunk(id, item.path("sourceTitle").asText("")));
                        }
                    }
                } catch (Exception ignored) {
                    // not a JSON tool result
                }
            }
        }
        return new ArrayList<>(chunks.values());
    }

    private record Next(int turnIndex, int roundIndex, GoldenRound round) {
    }

    private synchronized Next next(String call) {
        if (turnIdx < 0) {
            turnIdx = 0;
            roundIdx = 0;
        }
        if (turnIdx >= turns.size() || roundIdx >= turns.get(turnIdx).rounds().size()) {
            String msg = "Golden rounds exhausted for scenario=" + scenario
                    + " turn=" + turnIdx + " round=" + roundIdx + " (" + call + ") — " + NEED_RE_RECORD;
            problems.add(msg);
            throw new IllegalStateException(msg);
        }
        GoldenRound round = turns.get(turnIdx).rounds().get(roundIdx);
        Next next = new Next(turnIdx, roundIdx, round);
        roundIdx++;
        consumed++;
        return next;
    }

    private void reportUnconsumed(int turn) {
        if (turn >= turns.size()) {
            return;
        }
        int size = turns.get(turn).rounds().size();
        if (roundIdx < size) {
            problems.add("scenario=" + scenario + " turn " + turn + ": " + (size - roundIdx)
                    + " recorded round(s) not consumed — " + NEED_RE_RECORD);
        }
    }

    private static List<ToolCall> toToolCalls(Next next) {
        List<ToolCall> calls = new ArrayList<>();
        List<GoldenToolCall> recorded = next.round().toolCalls();
        for (int i = 0; i < recorded.size(); i++) {
            GoldenToolCall c = recorded.get(i);
            calls.add(new ToolCall("replay-" + next.turnIndex() + "-" + next.roundIndex() + "-" + i, c.name(), c.arguments()));
        }
        return calls;
    }

    /** Splits text into up to {@link #MAX_CHUNKS} deltas on code-point boundaries. */
    static List<String> chunks(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        int[] cps = content.codePoints().toArray();
        int n = Math.min(MAX_CHUNKS, cps.length);
        int size = (int) Math.ceil(cps.length / (double) n);
        List<String> out = new ArrayList<>(n);
        for (int start = 0; start < cps.length; start += size) {
            int end = Math.min(cps.length, start + size);
            out.add(new String(cps, start, end - start));
        }
        return out;
    }

    private static void emit(String content, Consumer<String> onDelta) {
        if (onDelta == null) {
            return;
        }
        for (String piece : chunks(content)) {
            onDelta.accept(piece);
        }
    }

    /**
     * Parses a legacy phase-1 decision payload (compact {@code {"type":"tool_calls"|"final_text"}} or an
     * OpenAI chat.completion body). Kept for {@code Phase1DecisionParser}.
     */
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

            if (root.has("calls") && root.path("calls").isArray()) {
                List<ToolCall> toolCalls = parseToolCallsNode(root);
                if (!toolCalls.isEmpty()) {
                    return new ToolCalls(toolCalls);
                }
            }
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
}
