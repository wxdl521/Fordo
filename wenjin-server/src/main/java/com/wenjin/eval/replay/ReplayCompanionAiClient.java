package com.wenjin.eval.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.golden.GoldenFile.GoldenRound;
import com.wenjin.eval.golden.GoldenFile.GoldenToolCall;
import com.wenjin.eval.runner.TurnAware;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * REPLAY {@link CompanionAiClient} over a golden v2 file: every LLM call consumes the next recorded
 * round of the current scenario turn, in order.
 * <ul>
 *   <li>{@link #streamTurn}: content is delivered through {@code onContentDelta} in a few chunks (exercising
 *       the orchestrator's streaming path), tool calls are returned as recorded;</li>
 *   <li>legacy {@link #stream}/{@link #streamMessages} fallbacks consume one round and stream its content;</li>
 *   <li>{@link #beginTurn()} moves to the next scenario turn (called by the eval turn runner).</li>
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
        emit(next.round().content(), onContentDelta);
        return new StreamTurnResult(next.round().content(), toToolCalls(next), null, next.round().finishReason());
    }

    @Override
    public void stream(String systemPrompt, List<ChatMsg> history, String userMessage,
                       Consumer<String> onToken) {
        emit(next("stream").round().content(), onToken);
    }

    @Override
    public void streamMessages(List<Map<String, Object>> messages, Consumer<String> onToken) {
        emit(next("streamMessages").round().content(), onToken);
    }

    @Override
    public TurnOutcome completeWithTools(List<Map<String, Object>> messages,
                                         List<Map<String, Object>> toolSchemas) {
        Next next = next("completeWithTools");
        List<ToolCall> calls = toToolCalls(next);
        return calls.isEmpty() ? new FinalText(next.round().content()) : new ToolCalls(calls);
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
