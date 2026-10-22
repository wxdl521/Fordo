package com.wenjin.eval.metrics;

import com.wenjin.eval.judge.JudgeScore;
import com.wenjin.eval.judge.JudgeVerdict;
import com.wenjin.eval.metrics.EvalSuiteSummary.ScenarioStats;
import com.wenjin.eval.metrics.EvalSuiteSummary.Stats;
import com.wenjin.eval.report.EvalReportCase;
import com.wenjin.eval.scenario.EvalScenarioDocument;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure aggregation of eval runs into {@link EvalSuiteSummary}. */
public final class EvalSuiteAggregator {

    /** Expected tool selection of a scenario (mustCall all hit, none of mustNot called). */
    public record ToolExpectation(List<String> mustCall, List<String> mustNot) {
        public ToolExpectation {
            mustCall = mustCall == null ? List.of() : List.copyOf(mustCall);
            mustNot = mustNot == null ? List.of() : List.copyOf(mustNot);
        }

        public static ToolExpectation fromScenario(EvalScenarioDocument doc) {
            if (doc == null || doc.hard() == null) {
                return new ToolExpectation(List.of(), List.of());
            }
            return new ToolExpectation(doc.hard().mustCallTools(), doc.hard().mustNotCallTools());
        }

        boolean satisfiedBy(List<String> called) {
            return called.containsAll(mustCall) && mustNot.stream().noneMatch(called::contains);
        }
    }

    private EvalSuiteAggregator() {
    }

    public static EvalSuiteSummary aggregate(List<EvalReportCase> runs,
                                             Map<String, ToolExpectation> expectations,
                                             EvalProperties.Price price,
                                             Instant generatedAt) {
        List<EvalReportCase> all = runs == null ? List.of() : runs;
        Map<String, ToolExpectation> exp = expectations == null ? Map.of() : expectations;
        Map<String, List<EvalReportCase>> byCode = new TreeMap<>();
        for (EvalReportCase c : all) {
            byCode.computeIfAbsent(c.outcome().scenarioCode(), k -> new ArrayList<>()).add(c);
        }
        List<ScenarioStats> scenarios = new ArrayList<>();
        byCode.forEach((code, list) -> scenarios.add(new ScenarioStats(code, stats(list, exp, price))));
        return new EvalSuiteSummary((generatedAt == null ? Instant.now() : generatedAt).toString(),
                stats(all, exp, price), scenarios);
    }

    static Stats stats(List<EvalReportCase> cases, Map<String, ToolExpectation> exp, EvalProperties.Price price) {
        List<EvalReportCase> ran = cases.stream().filter(c -> !c.outcome().skipped()).toList();
        int skipped = cases.size() - ran.size();
        int n = ran.size();

        long hardPass = ran.stream().filter(c -> c.outcome().hardPass()).count();
        long overallPass = ran.stream()
                .filter(c -> c.outcome().hardPass() && c.verdict().status() != JudgeVerdict.Status.FAIL).count();

        int toolN = 0;
        int toolOk = 0;
        for (EvalReportCase c : ran) {
            ToolExpectation te = exp.get(c.outcome().scenarioCode());
            if (te != null) {
                toolN++;
                if (te.satisfiedBy(c.outcome().toolsCalled())) {
                    toolOk++;
                }
            }
        }

        List<Double> ttft = new ArrayList<>();
        List<Double> total = new ArrayList<>();
        double tokIn = 0;
        double tokOut = 0;
        int tokN = 0;
        for (EvalReportCase c : ran) {
            EvalCaseMetrics m = c.outcome().metrics();
            if (m == null) {
                continue;
            }
            if (m.ttftMs() != null) {
                ttft.add(m.ttftMs().doubleValue());
            }
            if (m.totalMs() != null) {
                total.add(m.totalMs().doubleValue());
            }
            if (m.tokensIn() > 0 || m.tokensOut() > 0) {
                tokIn += m.tokensIn();
                tokOut += m.tokensOut();
                tokN++;
            }
        }

        Double cost = null;
        if (price != null && (price.getInputPerMtok() > 0 || price.getOutputPerMtok() > 0) && tokN > 0) {
            cost = tokIn / 1_000_000d * price.getInputPerMtok() + tokOut / 1_000_000d * price.getOutputPerMtok();
        }

        Map<String, double[]> dim = new LinkedHashMap<>();
        int judged = 0;
        int judgePass = 0;
        for (EvalReportCase c : ran) {
            if (c.judge() == null) {
                continue;
            }
            judged++;
            if (c.verdict().status() == JudgeVerdict.Status.PASS) {
                judgePass++;
            }
            if (c.judge().success()) {
                for (JudgeScore s : c.judge().scores()) {
                    double[] acc = dim.computeIfAbsent(s.key(), k -> new double[2]);
                    acc[0] += s.score();
                    acc[1]++;
                }
            }
        }
        Map<String, Double> dimMean = new LinkedHashMap<>();
        dim.forEach((k, v) -> dimMean.put(k, v[0] / v[1]));

        return new Stats(
                n,
                skipped,
                n == 0 ? 0 : (double) hardPass / n,
                n == 0 ? 0 : (double) overallPass / n,
                toolN == 0 ? null : (double) toolOk / toolN,
                percentile(ttft, 50), percentile(ttft, 95),
                percentile(total, 50), percentile(total, 95),
                tokN == 0 ? null : tokIn / tokN,
                tokN == 0 ? null : tokOut / tokN,
                cost,
                dimMean,
                judged == 0 ? null : (double) judgePass / judged,
                judged);
    }

    /** Nearest-rank percentile; null for an empty list. */
    public static Double percentile(List<Double> values, double p) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100d * sorted.size());
        return sorted.get(Math.min(sorted.size(), Math.max(1, rank)) - 1);
    }
}
