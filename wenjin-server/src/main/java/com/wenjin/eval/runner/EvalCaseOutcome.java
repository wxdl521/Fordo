package com.wenjin.eval.runner;

import com.wenjin.eval.hard.HardAssertResult;

import java.util.List;
import java.util.Objects;

/**
 * Structured result of one scenario execution (skeleton for suite/report later).
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
        List<String> toolsCalled
) {
    public EvalCaseOutcome {
        Objects.requireNonNull(scenarioCode, "scenarioCode");
        Objects.requireNonNull(mode, "mode");
        toolsCalled = toolsCalled == null ? List.of() : List.copyOf(toolsCalled);
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
                List.of());
    }

    public static EvalCaseOutcome completed(String scenarioCode,
                                            EvalMode mode,
                                            HardAssertResult hardResult,
                                            Long runId,
                                            Long conversationId,
                                            String finalResponse,
                                            List<String> toolsCalled) {
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
                toolsCalled);
    }
}
