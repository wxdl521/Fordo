package com.wenjin.eval.runner;

import com.wenjin.eval.hard.HardAssertResult;
import com.wenjin.eval.metrics.EvalCaseMetrics;

import java.util.List;
import java.util.Objects;

/**
 * Structured result of one scenario execution.
 * <p>{@code metrics} is nullable (REPLAY / legacy callers).
 */
public record EvalCaseOutcome(
        String scenarioCode,
        EvalMode mode,
        boolean skipped,
        String skipReason,
        boolean hardPass,
        HardAssertResult hardResult,
        Long runId,
        Long conversationId,
        String finalResponse,
        List<String> toolsCalled,
        EvalCaseMetrics metrics
) {
    public EvalCaseOutcome {
        Objects.requireNonNull(scenarioCode, "scenarioCode");
        Objects.requireNonNull(mode, "mode");
        toolsCalled = toolsCalled == null ? List.of() : List.copyOf(toolsCalled);
    }

    /** Backward-compatible constructor (metrics = null). */
    public EvalCaseOutcome(String scenarioCode, EvalMode mode, boolean skipped, String skipReason,
                           boolean hardPass, HardAssertResult hardResult, Long runId, Long conversationId,
                           String finalResponse, List<String> toolsCalled) {
        this(scenarioCode, mode, skipped, skipReason, hardPass, hardResult, runId, conversationId,
                finalResponse, toolsCalled, null);
    }

    public static EvalCaseOutcome skipped(String scenarioCode, EvalMode mode, String reason) {
        return new EvalCaseOutcome(
                scenarioCode,
                mode,
                true,
                reason,
                false,
                null,
                null,
                null,
                null,
                List.of(),
                null);
    }

    public static EvalCaseOutcome completed(String scenarioCode,
                                            EvalMode mode,
                                            HardAssertResult hardResult,
                                            Long runId,
                                            Long conversationId,
                                            String finalResponse,
                                            List<String> toolsCalled) {
        return completed(scenarioCode, mode, hardResult, runId, conversationId, finalResponse, toolsCalled, null);
    }

    public static EvalCaseOutcome completed(String scenarioCode,
                                            EvalMode mode,
                                            HardAssertResult hardResult,
                                            Long runId,
                                            Long conversationId,
                                            String finalResponse,
                                            List<String> toolsCalled,
                                            EvalCaseMetrics metrics) {
        boolean pass = hardResult != null && hardResult.passed();
        return new EvalCaseOutcome(
                scenarioCode,
                mode,
                false,
                null,
                pass,
                hardResult,
                runId,
                conversationId,
                finalResponse,
                toolsCalled,
                metrics);
    }

    public EvalCaseOutcome withMetrics(EvalCaseMetrics m) {
        return new EvalCaseOutcome(scenarioCode, mode, skipped, skipReason, hardPass, hardResult, runId,
                conversationId, finalResponse, toolsCalled, m);
    }
}
