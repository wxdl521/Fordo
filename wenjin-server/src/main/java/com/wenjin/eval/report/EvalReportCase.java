package com.wenjin.eval.report;

import com.wenjin.eval.judge.JudgeResult;
import com.wenjin.eval.judge.JudgeVerdict;
import com.wenjin.eval.runner.EvalCaseOutcome;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension;

import java.util.List;
import java.util.Objects;

/**
 * One scenario row for the eval report.
 *
 * @param judge      nullable (judge not run)
 * @param dimensions scenario judge dimensions carrying thresholds; empty = no threshold enforcement
 */
public record EvalReportCase(
        EvalCaseOutcome outcome,
        JudgeResult judge,
        List<JudgeDimension> dimensions
) {
    public EvalReportCase {
        Objects.requireNonNull(outcome, "outcome");
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    }

    public EvalReportCase(EvalCaseOutcome outcome, JudgeResult judge) {
        this(outcome, judge, List.of());
    }

    public static EvalReportCase of(EvalCaseOutcome outcome) {
        return new EvalReportCase(outcome, null, List.of());
    }

    public static EvalReportCase of(EvalCaseOutcome outcome, JudgeResult judge) {
        return new EvalReportCase(outcome, judge, List.of());
    }

    public static EvalReportCase of(EvalCaseOutcome outcome, JudgeResult judge, List<JudgeDimension> dimensions) {
        return new EvalReportCase(outcome, judge, dimensions);
    }

    public JudgeVerdict verdict() {
        return JudgeVerdict.evaluate(judge, dimensions);
    }
}
