package com.wenjin.eval.judge;

import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension;

import java.util.ArrayList;
import java.util.List;

/**
 * Threshold enforcement over a {@link JudgeResult}: passes only if every dimension score is
 * at least its threshold. A missing judge is {@link Status#NA} (not a failure); a judge that
 * failed its JSON contract is {@link Status#FAIL}.
 */
public record JudgeVerdict(Status status, List<DimensionVerdict> dimensions) {

    public enum Status { PASS, FAIL, NA }

    /** @param score null when the judge returned no score for the dimension */
    public record DimensionVerdict(String key, Integer score, Integer threshold, boolean passed) {}

    public JudgeVerdict {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    }

    public boolean passed() {
        return status == Status.PASS;
    }

    public boolean notApplicable() {
        return status == Status.NA;
    }

    public List<String> failedKeys() {
        return dimensions.stream().filter(d -> !d.passed()).map(DimensionVerdict::key).toList();
    }

    public static JudgeVerdict notRun() {
        return new JudgeVerdict(Status.NA, List.of());
    }

    public static JudgeVerdict evaluate(JudgeResult judge, List<JudgeDimension> dimensions) {
        if (judge == null) {
            return notRun();
        }
        if (!judge.success()) {
            return new JudgeVerdict(Status.FAIL, List.of());
        }
        List<DimensionVerdict> out = new ArrayList<>();
        boolean all = true;
        if (dimensions != null) {
            for (JudgeDimension d : dimensions) {
                if (d == null || d.key() == null) {
                    continue;
                }
                Integer score = judge.scores().stream()
                        .filter(s -> s.key().equals(d.key()))
                        .map(JudgeScore::score)
                        .findFirst().orElse(null);
                boolean ok = d.threshold() == null || (score != null && score >= d.threshold());
                all &= ok;
                out.add(new DimensionVerdict(d.key(), score, d.threshold(), ok));
            }
        }
        return new JudgeVerdict(all ? Status.PASS : Status.FAIL, out);
    }
}
