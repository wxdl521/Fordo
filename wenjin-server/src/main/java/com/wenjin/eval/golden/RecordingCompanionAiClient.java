package com.wenjin.eval.golden;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.eval.runner.TurnAware;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * RECORD decorator: forwards every call to the real client and captures what the model returned,
 * grouped by scenario turn ({@link #beginTurn()}), for {@link GoldenRecorder}.
 * <p>Failed calls are not recorded (a replayed run gets the same failure from fault injection, not from
 * the golden). Legacy {@code stream}/{@code streamMessages} fallbacks are recorded as content-only rounds.
 */
public final class RecordingCompanionAiClient implements CompanionAiClient, TurnAware {

    private final CompanionAiClient delegate;
    private final List<List<StreamTurnResult>> turns = new ArrayList<>();

    public RecordingCompanionAiClient(CompanionAiClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public synchronized void beginTurn() {
        turns.add(new ArrayList<>());
        if (delegate instanceof TurnAware aware) {
            aware.beginTurn();
        }
    }

    /** Recorded rounds per turn (defensive copy). */
    public synchronized List<List<StreamTurnResult>> recordedTurns() {
        List<List<StreamTurnResult>> copy = new ArrayList<>();
        for (List<StreamTurnResult> t : turns) {
            copy.add(List.copyOf(t));
        }
        return copy;
    }

    private synchronized void record(StreamTurnResult result) {
        if (turns.isEmpty()) {
            turns.add(new ArrayList<>());
        }
        turns.get(turns.size() - 1).add(result);
    }

    @Override
    public StreamTurnResult streamTurn(List<Map<String, Object>> messages,
                                       List<Map<String, Object>> toolSchemas,
                                       ToolChoice toolChoice,
                                       Consumer<String> onContentDelta) {
        StreamTurnResult result = delegate.streamTurn(messages, toolSchemas, toolChoice, onContentDelta);
        if (result != null) {
            record(result);
        }
        return result;
    }

    @Override
    public void stream(String systemPrompt, List<ChatMsg> history, String userMessage, Consumer<String> onToken) {
        StringBuilder sb = new StringBuilder();
        delegate.stream(systemPrompt, history, userMessage, tee(sb, onToken));
        record(new StreamTurnResult(sb.toString(), List.of(), null, "stop"));
    }

    @Override
    public void streamMessages(List<Map<String, Object>> messages, Consumer<String> onToken) {
        StringBuilder sb = new StringBuilder();
        delegate.streamMessages(messages, tee(sb, onToken));
        record(new StreamTurnResult(sb.toString(), List.of(), null, "stop"));
    }

    @Override
    public TurnOutcome completeWithTools(List<Map<String, Object>> messages, List<Map<String, Object>> toolSchemas) {
        TurnOutcome outcome = delegate.completeWithTools(messages, toolSchemas);
        if (outcome instanceof ToolCalls calls) {
            record(new StreamTurnResult("", calls.calls(), null, "tool_calls"));
        } else if (outcome instanceof FinalText text) {
            record(new StreamTurnResult(text.text(), List.of(), null, "stop"));
        }
        return outcome;
    }

    private static Consumer<String> tee(StringBuilder sb, Consumer<String> downstream) {
        return token -> {
            if (token != null) {
                sb.append(token);
            }
            if (downstream != null) {
                downstream.accept(token);
            }
        };
    }
}
