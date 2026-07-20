package com.wenjin.eval.report;

import com.wenjin.eval.judge.JudgeResult;
import com.wenjin.eval.runner.EvalCaseOutcome;

import java.util.Objects;

/**
 * One scenario row for the markdown eval report.
 */
public record EvalReportCase(
        EvalCaseOutcome outcome,
        JudgeResult judge
) {
    public EvalReportCase {
        Objects.requireNonNull(outcome, "outcome");
        // judge may be null (not run / skipped)
    }

    public static EvalReportCase of(EvalCaseOutcome outcome) {
        return new EvalReportCase(outcome, null);
    }

    public static EvalReportCase of(EvalCaseOutcome outcome, JudgeResult judge) {
        return new EvalReportCase(outcome, judge);
    }
}
