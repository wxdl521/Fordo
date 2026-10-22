package com.wenjin.eval.runner;

import com.wenjin.eval.scenario.EvalScenarioDocument;

/**
 * Per-scenario hooks around an executor run (AI client wiring, fault injection, tool budget override,
 * golden recording). Runs after the fixture has prepared the persona and before the first turn.
 */
public interface ScenarioLifecycle {

    ScenarioLifecycle NONE = new ScenarioLifecycle() {};

    default void beforeScenario(EvalScenarioDocument scenario) {
    }

    /**
     * Always called (also when a turn threw). May return an amended outcome, e.g. with replay mismatches.
     */
    default EvalCaseOutcome afterScenario(EvalScenarioDocument scenario, EvalCaseOutcome outcome) {
        return outcome;
    }
}
