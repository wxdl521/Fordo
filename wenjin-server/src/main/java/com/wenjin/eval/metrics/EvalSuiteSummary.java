package com.wenjin.eval.metrics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Aggregation over (possibly repeated) scenario runs. Serialised as {@code report.json}.
 * Nullable doubles mean "no data" (e.g. REPLAY has no latency, price 0 means cost not computed).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalSuiteSummary(String generatedAt, Stats overall, List<ScenarioStats> scenarios) {

    public EvalSuiteSummary {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScenarioStats(String code, Stats stats) {}

    /**
     * @param runs                   executed (non-skipped) runs
     * @param skipped                skipped runs
     * @param hardPassRate           share of runs whose hard asserts all passed
     * @param overallPassRate        share of runs with hard pass and judge verdict not FAIL
     * @param toolSelectionAccuracy  share of runs where all mustCall hit and no mustNot called
     * @param costUsd                estimated cost for all runs, null when prices are 0
     * @param judgeDimMean           mean judge score per dimension
     * @param judgeThresholdPassRate share of judged runs whose verdict is PASS
     * @param judgeRuns              runs with a judge result
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stats(
            int runs,
            int skipped,
            double hardPassRate,
            double overallPassRate,
            Double toolSelectionAccuracy,
            Double ttftP50,
            Double ttftP95,
            Double totalP50,
            Double totalP95,
            Double avgTokensIn,
            Double avgTokensOut,
            Double costUsd,
            Map<String, Double> judgeDimMean,
            Double judgeThresholdPassRate,
            int judgeRuns
    ) {
        public Stats {
            judgeDimMean = judgeDimMean == null ? Map.of() : Map.copyOf(judgeDimMean);
        }
    }
}
