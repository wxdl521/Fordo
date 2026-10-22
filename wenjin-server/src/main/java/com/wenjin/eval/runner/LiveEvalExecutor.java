package com.wenjin.eval.runner;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.eval.fixture.EvalFixture;
import com.wenjin.eval.hard.HardAssertChecker;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.ScenarioLoader;

import java.util.Objects;

/**
 * LIVE eval executor: prepares the persona via {@link EvalFixture}, applies the {@link ScenarioLifecycle}
 * (faults / tool budget / recording), drives the script turns through a {@link CompanionTurnRunner}
 * (the real companion path with {@code ServiceCompanionTurnRunner}) and runs hard asserts + metrics.
 * Suite-level repetition, judging and reporting live in {@code LiveEvalSuite}.
 */
public final class LiveEvalExecutor {

    private final ScenarioLoader scenarioLoader;
    private final CompanionTurnRunner turnRunner;
    private final HardAssertChecker hardAssertChecker;
    private final FeatureFlagsProperties featureFlags;
    private final EvalFixture fixture; // nullable
    private final ScenarioLifecycle lifecycle;

    public LiveEvalExecutor(ScenarioLoader scenarioLoader,
                            CompanionTurnRunner turnRunner,
                            HardAssertChecker hardAssertChecker,
                            FeatureFlagsProperties featureFlags,
                            EvalFixture fixture,
                            ScenarioLifecycle lifecycle) {
        this.scenarioLoader = Objects.requireNonNull(scenarioLoader, "scenarioLoader");
        this.turnRunner = Objects.requireNonNull(turnRunner, "turnRunner");
        this.hardAssertChecker = Objects.requireNonNull(hardAssertChecker, "hardAssertChecker");
        this.featureFlags = featureFlags;
        this.fixture = fixture;
        this.lifecycle = lifecycle == null ? ScenarioLifecycle.NONE : lifecycle;
    }

    public LiveEvalExecutor(ScenarioLoader scenarioLoader,
                            CompanionTurnRunner turnRunner,
                            HardAssertChecker hardAssertChecker,
                            FeatureFlagsProperties featureFlags,
                            EvalFixture fixture) {
        this(scenarioLoader, turnRunner, hardAssertChecker, featureFlags, fixture, null);
    }

    public LiveEvalExecutor(ScenarioLoader scenarioLoader,
                            CompanionTurnRunner turnRunner,
                            HardAssertChecker hardAssertChecker,
                            FeatureFlagsProperties featureFlags) {
        this(scenarioLoader, turnRunner, hardAssertChecker, featureFlags, null, null);
    }

    /** Run one scenario by code (e.g. S09). */
    public EvalCaseOutcome run(String scenarioCode) {
        if (!EvalEnabledGuard.isEnabled(featureFlags)) {
            return EvalCaseOutcome.skipped(
                    scenarioCode == null ? "?" : scenarioCode,
                    EvalMode.LIVE,
                    EvalEnabledGuard.SKIP_REASON);
        }
        EvalScenarioDocument scenario = scenarioLoader.loadByCode(scenarioCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + scenarioCode));
        return run(scenario);
    }

    /** Run a loaded scenario document. */
    public EvalCaseOutcome run(EvalScenarioDocument scenario) {
        return runDetailed(scenario).outcome();
    }

    /** Run a loaded scenario and keep the transcript. */
    public ScenarioRun runDetailed(EvalScenarioDocument scenario) {
        Objects.requireNonNull(scenario, "scenario");
        if (!EvalEnabledGuard.isEnabled(featureFlags)) {
            return new ScenarioRun(
                    EvalCaseOutcome.skipped(scenario.code(), EvalMode.LIVE, EvalEnabledGuard.SKIP_REASON), null);
        }
        return ScenarioDriver.drive(EvalMode.LIVE, scenario, turnRunner, hardAssertChecker, fixture, lifecycle);
    }
}
