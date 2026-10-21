package com.wenjin.eval.runner;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.eval.fixture.EvalFixture;
import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.golden.GoldenLoader;
import com.wenjin.eval.hard.HardAssertChecker;
import com.wenjin.eval.replay.ReplayCompanionAiClient;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.ScenarioLoader;

import java.nio.file.Path;
import java.util.Objects;

/**
 * REPLAY eval executor: same scenario loop as {@link LiveEvalExecutor}, mode={@link EvalMode#REPLAY}.
 * <p>With {@code ServiceCompanionTurnRunner} and a lifecycle that installs a {@link ReplayCompanionAiClient}
 * per scenario ({@code EvalScenarioEnvironment}), the real companion orchestration runs against recorded
 * model output — an orchestration regression test.
 */
public final class ReplayEvalExecutor {

    private final ScenarioLoader scenarioLoader;
    private final CompanionTurnRunner turnRunner;
    private final HardAssertChecker hardAssertChecker;
    private final FeatureFlagsProperties featureFlags;
    private final EvalFixture fixture; // nullable
    private final GoldenLoader goldenLoader;
    private final ScenarioLifecycle lifecycle;

    public ReplayEvalExecutor(ScenarioLoader scenarioLoader,
                              CompanionTurnRunner turnRunner,
                              HardAssertChecker hardAssertChecker,
                              FeatureFlagsProperties featureFlags,
                              EvalFixture fixture,
                              GoldenLoader goldenLoader,
                              ScenarioLifecycle lifecycle) {
        this.scenarioLoader = Objects.requireNonNull(scenarioLoader, "scenarioLoader");
        this.turnRunner = Objects.requireNonNull(turnRunner, "turnRunner");
        this.hardAssertChecker = Objects.requireNonNull(hardAssertChecker, "hardAssertChecker");
        this.featureFlags = featureFlags;
        this.fixture = fixture;
        this.goldenLoader = goldenLoader == null ? new GoldenLoader() : goldenLoader;
        this.lifecycle = lifecycle == null ? ScenarioLifecycle.NONE : lifecycle;
    }

    public ReplayEvalExecutor(ScenarioLoader scenarioLoader,
                              CompanionTurnRunner turnRunner,
                              HardAssertChecker hardAssertChecker,
                              FeatureFlagsProperties featureFlags,
                              EvalFixture fixture,
                              GoldenLoader goldenLoader) {
        this(scenarioLoader, turnRunner, hardAssertChecker, featureFlags, fixture, goldenLoader, null);
    }

    public ReplayEvalExecutor(ScenarioLoader scenarioLoader,
                              CompanionTurnRunner turnRunner,
                              HardAssertChecker hardAssertChecker,
                              FeatureFlagsProperties featureFlags,
                              EvalFixture fixture) {
        this(scenarioLoader, turnRunner, hardAssertChecker, featureFlags, fixture, new GoldenLoader(), null);
    }

    public ReplayEvalExecutor(ScenarioLoader scenarioLoader,
                              CompanionTurnRunner turnRunner,
                              HardAssertChecker hardAssertChecker,
                              FeatureFlagsProperties featureFlags) {
        this(scenarioLoader, turnRunner, hardAssertChecker, featureFlags, null, new GoldenLoader(), null);
    }

    public EvalCaseOutcome run(String scenarioCode) {
        if (!EvalEnabledGuard.isEnabled(featureFlags)) {
            return EvalCaseOutcome.skipped(
                    scenarioCode == null ? "?" : scenarioCode,
                    EvalMode.REPLAY,
                    EvalEnabledGuard.SKIP_REASON);
        }
        EvalScenarioDocument scenario = scenarioLoader.loadByCode(scenarioCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + scenarioCode));
        return run(scenario);
    }

    public EvalCaseOutcome run(EvalScenarioDocument scenario) {
        return runDetailed(scenario).outcome();
    }

    public ScenarioRun runDetailed(EvalScenarioDocument scenario) {
        Objects.requireNonNull(scenario, "scenario");
        if (!EvalEnabledGuard.isEnabled(featureFlags)) {
            return new ScenarioRun(
                    EvalCaseOutcome.skipped(scenario.code(), EvalMode.REPLAY, EvalEnabledGuard.SKIP_REASON), null);
        }
        return ScenarioDriver.drive(EvalMode.REPLAY, scenario, turnRunner, hardAssertChecker, fixture, lifecycle);
    }

    /** Load classpath golden {@code eval/golden/{code}.json} into a replay client. */
    public ReplayCompanionAiClient loadReplayClient(String scenarioCode) {
        GoldenFile golden = goldenLoader.loadFromClasspath(scenarioCode);
        return new ReplayCompanionAiClient(golden);
    }

    /** Load golden from filesystem path into a replay client. */
    public ReplayCompanionAiClient loadReplayClient(Path goldenPath) {
        GoldenFile golden = goldenLoader.load(goldenPath);
        return new ReplayCompanionAiClient(golden);
    }

    public GoldenLoader goldenLoader() {
        return goldenLoader;
    }
}
