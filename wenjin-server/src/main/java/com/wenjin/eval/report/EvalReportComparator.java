package com.wenjin.eval.report;

import com.wenjin.eval.metrics.EvalSuiteSummary;
import com.wenjin.eval.metrics.EvalSuiteSummary.ScenarioStats;
import com.wenjin.eval.metrics.EvalSuiteSummary.Stats;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Diffs two {@code report.json} summaries into a markdown table. */
public final class EvalReportComparator {

    private EvalReportComparator() {
    }

    public static String compare(Path baseline, Path candidate) {
        return compare(EvalSuiteReportWriter.readJson(baseline), EvalSuiteReportWriter.readJson(candidate));
    }

    public static String compare(EvalSuiteSummary base, EvalSuiteSummary cand) {
        StringBuilder sb = new StringBuilder("# Eval Comparison\n\n");
        sb.append("Baseline: `").append(base.generatedAt()).append("`  \nCandidate: `")
                .append(cand.generatedAt()).append("`\n\n");
        sb.append("| Metric | Baseline | Candidate | Delta |\n|--------|----------|-----------|-------|\n");
        metric(sb, "Hard pass rate", base.overall(), cand.overall(), Stats::hardPassRate, true);
        metric(sb, "Overall pass rate", base.overall(), cand.overall(), Stats::overallPassRate, true);
        metric(sb, "Tool selection accuracy", base.overall(), cand.overall(), Stats::toolSelectionAccuracy, true);
        metric(sb, "TTFT p50 (ms)", base.overall(), cand.overall(), Stats::ttftP50, false);
        metric(sb, "TTFT p95 (ms)", base.overall(), cand.overall(), Stats::ttftP95, false);
        metric(sb, "Total p50 (ms)", base.overall(), cand.overall(), Stats::totalP50, false);
        metric(sb, "Total p95 (ms)", base.overall(), cand.overall(), Stats::totalP95, false);
        metric(sb, "Avg tokens in", base.overall(), cand.overall(), Stats::avgTokensIn, false);
        metric(sb, "Avg tokens out", base.overall(), cand.overall(), Stats::avgTokensOut, false);
        metric(sb, "Cost (USD)", base.overall(), cand.overall(), Stats::costUsd, false);
        metric(sb, "Judge threshold pass rate", base.overall(), cand.overall(), Stats::judgeThresholdPassRate, true);

        List<String> dims = new ArrayList<>(base.overall().judgeDimMean().keySet());
        cand.overall().judgeDimMean().keySet().stream().filter(k -> !dims.contains(k)).forEach(dims::add);
        dims.sort(String::compareTo);
        for (String d : dims) {
            metric(sb, "Judge mean: " + d, base.overall(), cand.overall(), s -> s.judgeDimMean().get(d), false);
        }

        Map<String, Stats> baseBy = new HashMap<>();
        base.scenarios().forEach(s -> baseBy.put(s.code(), s.stats()));
        List<String> newFailures = new ArrayList<>();
        for (ScenarioStats s : cand.scenarios()) {
            boolean candFail = s.stats().runs() > 0 && s.stats().overallPassRate() < 1.0;
            Stats b = baseBy.get(s.code());
            boolean baseFail = b != null && b.runs() > 0 && b.overallPassRate() < 1.0;
            if (candFail && !baseFail) {
                newFailures.add(s.code() + " (overall " + pct(s.stats().overallPassRate()) + ")");
            }
        }
        sb.append("\n## New failing scenarios\n\n");
        if (newFailures.isEmpty()) {
            sb.append("_(none)_\n");
        } else {
            newFailures.forEach(f -> sb.append("- ").append(f).append('\n'));
        }
        return sb.toString();
    }

    private static void metric(StringBuilder sb, String name, Stats b, Stats c,
                               Function<Stats, Double> f, boolean percent) {
        Double bv = f.apply(b);
        Double cv = f.apply(c);
        sb.append("| ").append(name).append(" | ").append(show(bv, percent)).append(" | ")
                .append(show(cv, percent)).append(" | ");
        if (bv == null || cv == null) {
            sb.append("—");
        } else {
            double d = cv - bv;
            sb.append(percent ? String.format(Locale.ROOT, "%+.1f pp", d * 100)
                    : String.format(Locale.ROOT, "%+.2f", d));
        }
        sb.append(" |\n");
    }

    private static String show(Double v, boolean percent) {
        if (v == null) {
            return "—";
        }
        return percent ? pct(v) : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }
}
