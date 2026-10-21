package com.wenjin.eval.runner;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.eval.golden.RecordingCompanionAiClient;
import com.wenjin.eval.scenario.EvalScenarioDocument.LiveFaults;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The {@link CompanionAiClient} the companion service talks to under the {@code eval} profile.
 * <p>Per scenario it is assembled as {@code faults( record( source ) )} where {@code source} is either the
 * base client (LIVE: the real OpenAI-compatible client, or a stub in tests) or a scenario-specific client
 * (REPLAY: {@code ReplayCompanionAiClient}). Recording sits inside fault injection so goldens only ever
 * contain real model output. Scenarios run sequentially; configuration is not meant for concurrent use.
 */
public final class EvalCompanionAiClient implements CompanionAiClient, TurnAware {

    private volatile CompanionAiClient base;
    private volatile CompanionAiClient active;
    private volatile RecordingCompanionAiClient recorder;
    private volatile FaultInjectingCompanionAiClient faults;

    public EvalCompanionAiClient(CompanionAiClient base) {
        this.base = Objects.requireNonNull(base, "base");
        this.active = base;
    }

    /** Replace the base (LIVE) client, e.g. with a stub in pipeline tests. Resets scenario layers. */
    public synchronized void useBase(CompanionAiClient newBase) {
        this.base = Objects.requireNonNull(newBase, "base");
        clearScenario();
    }

    public CompanionAiClient base() {
        return base;
    }

    /**
     * Configure layers for the next scenario.
     *
     * @param scenarioSource client for this scenario (REPLAY), or null to use the base client (LIVE)
     * @param liveFaults     scenario fault spec, nullable
     * @param record         wrap the source in a {@link RecordingCompanionAiClient}
     */
    public synchronized void configureScenario(CompanionAiClient scenarioSource, LiveFaults liveFaults, boolean record) {
        CompanionAiClient chain = scenarioSource == null ? base : scenarioSource;
        recorder = null;
        faults = null;
        if (record) {
            recorder = new RecordingCompanionAiClient(chain);
            chain = recorder;
        }
        int failures = liveFaults == null || liveFaults.llmFailures() == null ? 0 : liveFaults.llmFailures();
        if (failures > 0) {
            faults = new FaultInjectingCompanionAiClient(chain, failures);
            chain = faults;
        }
        active = chain;
    }

    public synchronized void clearScenario() {
        recorder = null;
        faults = null;
        active = base;
    }

    /** Recorder of the current scenario, null when not recording. */
    public RecordingCompanionAiClient recorder() {
        return recorder;
    }

    /** Fault layer of the current scenario, null when no faults are injected. */
    public FaultInjectingCompanionAiClient faults() {
        return faults;
    }

    CompanionAiClient active() {
        return active;
    }

    @Override
    public void beginTurn() {
        if (active instanceof TurnAware aware) {
            aware.beginTurn();
        }
    }

    @Override
    public StreamTurnResult streamTurn(List<Map<String, Object>> messages,
                                       List<Map<String, Object>> toolSchemas,
                                       ToolChoice toolChoice,
                                       Consumer<String> onContentDelta) {
        return active.streamTurn(messages, toolSchemas, toolChoice, onContentDelta);
    }

    @Override
    public void stream(String systemPrompt, List<ChatMsg> history, String userMessage, Consumer<String> onToken) {
        active.stream(systemPrompt, history, userMessage, onToken);
    }

    @Override
    public TurnOutcome completeWithTools(List<Map<String, Object>> messages, List<Map<String, Object>> toolSchemas) {
        return active.completeWithTools(messages, toolSchemas);
    }

    @Override
    public void streamMessages(List<Map<String, Object>> messages, Consumer<String> onToken) {
        active.streamMessages(messages, onToken);
    }
}
