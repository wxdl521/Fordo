package com.wenjin.eval.runner;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Scenario {@code liveFaults.llmFailures}: the first N LLM calls of a scenario (any method) fail the way a
 * provider failure does ({@link BusinessException} {@code AI_ERROR}), so the degrade path can be exercised
 * deterministically in LIVE, REPLAY and RECORD alike. The message deliberately avoids the word "timeout".
 */
public final class FaultInjectingCompanionAiClient implements CompanionAiClient, TurnAware {

    public static final String FAULT_MESSAGE = "eval fault injection: simulated LLM provider failure";

    private final CompanionAiClient delegate;
    private final AtomicInteger failuresLeft;
    private final AtomicInteger injected = new AtomicInteger();

    public FaultInjectingCompanionAiClient(CompanionAiClient delegate, int llmFailures) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.failuresLeft = new AtomicInteger(Math.max(0, llmFailures));
    }

    /** Number of failures injected so far. */
    public int injected() {
        return injected.get();
    }

    @Override
    public void beginTurn() {
        if (delegate instanceof TurnAware aware) {
            aware.beginTurn();
        }
    }

    private void maybeFail() {
        if (failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            injected.incrementAndGet();
            throw new BusinessException(ResultCode.AI_ERROR, FAULT_MESSAGE);
        }
    }

    @Override
    public StreamTurnResult streamTurn(List<Map<String, Object>> messages,
                                       List<Map<String, Object>> toolSchemas,
                                       ToolChoice toolChoice,
                                       Consumer<String> onContentDelta) {
        maybeFail();
        return delegate.streamTurn(messages, toolSchemas, toolChoice, onContentDelta);
    }

    @Override
    public void stream(String systemPrompt, List<ChatMsg> history, String userMessage, Consumer<String> onToken) {
        maybeFail();
        delegate.stream(systemPrompt, history, userMessage, onToken);
    }

    @Override
    public TurnOutcome completeWithTools(List<Map<String, Object>> messages, List<Map<String, Object>> toolSchemas) {
        maybeFail();
        return delegate.completeWithTools(messages, toolSchemas);
    }

    @Override
    public void streamMessages(List<Map<String, Object>> messages, Consumer<String> onToken) {
        maybeFail();
        delegate.streamMessages(messages, onToken);
    }
}
