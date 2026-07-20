package com.wenjin.eval.runner;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.eval.collect.CollectedReply;
import com.wenjin.eval.fixture.EvalFixture;
import com.wenjin.eval.hard.EvalStepView;
import com.wenjin.eval.hard.HardAssertChecker;
import com.wenjin.eval.hard.HardAssertInput;
import com.wenjin.eval.hard.HardAssertResult;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.EvalScenarioDocument.ScriptTurn;
import com.wenjin.eval.scenario.ScenarioLoader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * LIVE eval executor skeleton (Task 5).
 * <p>Loads a scenario, optionally prepares persona via {@link EvalFixture}, drives script turns
 * through {@link CompanionTurnRunner} (real companion path or mocked AI), runs hard asserts.
 * RECORD / REPLAY / Judge are later tasks.
 */
public final class LiveEvalExecutor {

    private final ScenarioLoader scenarioLoader;
    private final CompanionTurnRunner turnRunner;
    private final HardAssertChecker hardAssertChecker;
    private final FeatureFlagsProperties featureFlags;
    private final EvalFixture fixture; // nullable

    public LiveEvalExecutor(ScenarioLoader scenarioLoader,
                            CompanionTurnRunner turnRunner,
                            HardAssertChecker hardAssertChecker,
                            FeatureFlagsProperties featureFlags,
                            EvalFixture fixture) {
        this.scenarioLoader = Objects.requireNonNull(scenarioLoader, "scenarioLoader");
        this.turnRunner = Objects.requireNonNull(turnRunner, "turnRunner");
        this.hardAssertChecker = Objects.requireNonNull(hardAssertChecker, "hardAssertChecker");
        this.featureFlags = featureFlags;
        this.fixture = fixture;
    }

    public LiveEvalExecutor(ScenarioLoader scenarioLoader,
                            CompanionTurnRunner turnRunner,
                            HardAssertChecker hardAssertChecker,
                            FeatureFlagsProperties featureFlags) {
        this(scenarioLoader, turnRunner, hardAssertChecker, featureFlags, null);
    }

    /**
     * Run one scenario by code (e.g. S09).
     */
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

    /**
     * Run a loaded scenario document.
     */
    public EvalCaseOutcome run(EvalScenarioDocument scenario) {
        Objects.requireNonNull(scenario, "scenario");
        if (!EvalEnabledGuard.isEnabled(featureFlags)) {
            return EvalCaseOutcome.skipped(scenario.code(), EvalMode.LIVE, EvalEnabledGuard.SKIP_REASON);
        }

        long studentId;
        long courseId;
        Set<String> whitelist;
        if (fixture != null) {
            fixture.prepareScenario(scenario);
            studentId = fixture.world().studentId();
            courseId = fixture.world().courseId();
            whitelist = fixture.world().nodeCodes();
        } else {
            studentId = 0L;
            courseId = 0L;
            whitelist = Set.of();
        }

        Long conversationId = null;
        Long lastRunId = null;
        List<EvalStepView> allSteps = new ArrayList<>();
        CollectedReply lastReply = new CollectedReply("", List.of(), List.of());
        Set<String> toolsCalled = new LinkedHashSet<>();

        for (ScriptTurn turn : scenario.script()) {
            CompanionTurnRunner.TurnResult result = turnRunner.runTurn(
                    new CompanionTurnRunner.TurnRequest(
                            studentId,
                            courseId,
                            conversationId,
                            turn.user()));
            conversationId = result.conversationId();
            lastRunId = result.runId();
            if (result.steps() != null) {
                allSteps.addAll(result.steps());
            }
            if (result.reply() != null) {
                lastReply = result.reply();
                toolsCalled.addAll(result.reply().toolNamesCalled());
            }
            for (EvalStepView step : result.steps()) {
                if (step.isToolCall() && step.name() != null && !step.name().isBlank()) {
                    toolsCalled.add(step.name());
                }
            }
        }

        HardAssertResult hardResult = hardAssertChecker.check(new HardAssertInput(
                allSteps,
                lastReply.text(),
                scenario.hard(),
                whitelist));

        return EvalCaseOutcome.completed(
                scenario.code(),
                EvalMode.LIVE,
                hardResult,
                lastRunId,
                conversationId,
                lastReply.text(),
                List.copyOf(toolsCalled));
    }
}
