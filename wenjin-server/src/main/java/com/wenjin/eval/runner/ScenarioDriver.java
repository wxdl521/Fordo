package com.wenjin.eval.runner;

import com.wenjin.eval.collect.CollectedReply;
import com.wenjin.eval.fixture.EvalFixture;
import com.wenjin.eval.hard.EvalStepView;
import com.wenjin.eval.hard.HardAssertChecker;
import com.wenjin.eval.hard.HardAssertFailure;
import com.wenjin.eval.hard.HardAssertInput;
import com.wenjin.eval.hard.HardAssertResult;
import com.wenjin.eval.metrics.EvalCaseMetrics;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.EvalScenarioDocument.ScriptTurn;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared scenario loop of the LIVE and REPLAY executors: fixture persona → lifecycle → script turns →
 * hard asserts + metrics. A turn that throws ends the scenario and is reported as an {@code execution}
 * hard-assert failure instead of aborting a whole suite.
 */
final class ScenarioDriver {

    static final String EXECUTION_CHECK = "execution";

    private ScenarioDriver() {
    }

    static ScenarioRun drive(EvalMode mode,
                             EvalScenarioDocument scenario,
                             CompanionTurnRunner turnRunner,
                             HardAssertChecker checker,
                             EvalFixture fixture,
                             ScenarioLifecycle lifecycle) {
        ScenarioLifecycle hooks = lifecycle == null ? ScenarioLifecycle.NONE : lifecycle;
        long studentId;
        long actingUserId;
        long courseId;
        Set<String> whitelist;
        if (fixture != null) {
            fixture.prepareScenario(scenario);
            studentId = fixture.world().studentId();
            actingUserId = scenario.forTeacher() ? fixture.world().teacherId() : studentId;
            courseId = fixture.world().courseId();
            whitelist = fixture.world().nodeCodes();
        } else {
            studentId = 0L;
            actingUserId = 0L;
            courseId = 0L;
            whitelist = Set.of();
        }

        Long conversationId = null;
        Long lastRunId = null;
        List<EvalStepView> allSteps = new ArrayList<>();
        CollectedReply lastReply = new CollectedReply("", List.of(), List.of());
        Set<String> toolsCalled = new LinkedHashSet<>();
        List<ScenarioRun.Exchange> exchanges = new ArrayList<>();
        Long ttftMs = null;
        Long totalMs = null;
        String executionError = null;

        EvalCaseOutcome outcome;
        VirtualMachineError fatal = null;
        try {
            hooks.beforeScenario(scenario);
            for (ScriptTurn turn : scenario.script()) {
                CompanionTurnRunner.TurnResult result;
                try {
                    result = turnRunner.runTurn(new CompanionTurnRunner.TurnRequest(
                            actingUserId, courseId, conversationId, turn.user(), scenario.resolvedActor()));
                } catch (RuntimeException e) {
                    executionError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    exchanges.add(new ScenarioRun.Exchange(turn.user(), "", List.of()));
                    break;
                }
                conversationId = result.conversationId();
                lastRunId = result.runId();
                allSteps.addAll(result.steps());
                Set<String> turnTools = new LinkedHashSet<>();
                if (result.reply() != null) {
                    lastReply = result.reply();
                    turnTools.addAll(result.reply().toolNamesCalled());
                }
                for (EvalStepView step : result.steps()) {
                    if (step.isToolCall() && !step.name().isBlank()) {
                        turnTools.add(step.name());
                    }
                }
                turnTools.removeAll(blockedTools(result.steps()));
                toolsCalled.addAll(turnTools);
                exchanges.add(new ScenarioRun.Exchange(turn.user(),
                        result.reply() == null ? "" : result.reply().text(), List.copyOf(turnTools),
                        CitationEvidence.from(result.steps())));
                if (ttftMs == null && result.ttftMs() != null) {
                    ttftMs = result.ttftMs();
                }
                if (result.totalMs() != null) {
                    totalMs = (totalMs == null ? 0L : totalMs) + result.totalMs();
                }
            }

            HardAssertResult hard = checker.check(new HardAssertInput(
                    allSteps, lastReply.text(), scenario.hard(), whitelist));
            if (executionError != null) {
                List<HardAssertFailure> failures = new ArrayList<>();
                failures.add(new HardAssertFailure(EXECUTION_CHECK, executionError));
                failures.addAll(hard.failures());
                hard = HardAssertResult.fail(failures);
            }
            outcome = EvalCaseOutcome.completed(
                    scenario.code(),
                    mode,
                    hard,
                    lastRunId,
                    conversationId,
                    lastReply.text(),
                    List.copyOf(toolsCalled),
                    EvalCaseMetrics.fromSteps(allSteps, ttftMs, totalMs));
        } catch (Throwable e) {
            // Throwable（含 AssertionError 等 Error）：afterScenario 必须执行以恢复共享覆盖（如 maxToolCalls）
            if (e instanceof VirtualMachineError vme) {
                fatal = vme;
            }
            outcome = EvalCaseOutcome.completed(
                    scenario.code(),
                    mode,
                    HardAssertResult.fail(List.of(new HardAssertFailure(EXECUTION_CHECK,
                            e.getClass().getSimpleName() + ": " + e.getMessage()))),
                    lastRunId,
                    conversationId,
                    lastReply.text(),
                    List.copyOf(toolsCalled),
                    EvalCaseMetrics.fromSteps(allSteps, ttftMs, totalMs));
        }
        outcome = hooks.afterScenario(scenario, outcome);
        if (fatal != null) {
            throw fatal; // 已恢复共享状态；JVM 级错误不吞
        }
        return new ScenarioRun(outcome, exchanges, scenario.resolvedActor());
    }

    /**
     * Tools the governor refused as {@code TOOL_NOT_ALLOWED} (not offered / wrong audience) and that never ran.
     * They are attempts, not calls: they do not count as {@code toolsCalled} (tool-selection accuracy), while the
     * GUARD step keeps counting against {@code maxToolCalls} and satisfies {@code guardCodesExpected}.
     */
    static Set<String> blockedTools(List<EvalStepView> steps) {
        Set<String> blocked = new LinkedHashSet<>();
        Set<String> executed = new LinkedHashSet<>();
        for (EvalStepView step : steps) {
            if (step.isGuard() && "TOOL_NOT_ALLOWED".equals(step.errorCode())) {
                blocked.add(step.name());
            } else if (step.isToolCall()) {
                executed.add(step.name());
            }
        }
        blocked.removeAll(executed);
        return blocked;
    }

    /** Copy of an outcome with extra hard failures appended. */
    static EvalCaseOutcome withExtraFailures(EvalCaseOutcome outcome, List<HardAssertFailure> extra) {
        if (extra == null || extra.isEmpty()) {
            return outcome;
        }
        List<HardAssertFailure> failures = new ArrayList<>();
        if (outcome.hardResult() != null) {
            failures.addAll(outcome.hardResult().failures());
        }
        failures.addAll(extra);
        return EvalCaseOutcome.completed(outcome.scenarioCode(), outcome.mode(), HardAssertResult.fail(failures),
                outcome.runId(), outcome.conversationId(), outcome.finalResponse(), outcome.toolsCalled(),
                outcome.metrics());
    }
}
