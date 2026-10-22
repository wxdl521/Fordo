package com.wenjin.eval.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.wenjin.eval.metrics.EvalSuiteSummary;
import com.wenjin.eval.metrics.EvalSuiteSummary.ScenarioStats;
import com.wenjin.eval.metrics.EvalSuiteSummary.Stats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Writes {@code report.md} + {@code report.json} for a suite run into an output directory. */
public final class EvalSuiteReportWriter {

    public static final String DEFAULT_BASE_DIR = "target/eval-live";
    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final EvalReportGenerator generator;

    public EvalSuiteReportWriter(EvalReportGenerator generator) {
        this.generator = generator == null ? new EvalReportGenerator() : generator;
    }

    /** {@code target/eval-live/<yyyyMMdd-HHmmss>/}. */
    public static Path defaultOutDir(Path baseDir, LocalDateTime now) {
        Path base = baseDir == null ? Path.of(DEFAULT_BASE_DIR) : baseDir;
        return base.resolve(DIR_FMT.format(now == null ? LocalDateTime.now() : now));
    }

    public Path write(Path outDir, List<EvalReportCase> runs, EvalSuiteSummary summary) {
        try {
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve("report.json"), MAPPER.writeValueAsString(summary));
            Files.writeString(outDir.resolve("report.md"), summaryMarkdown(summary) + "\n" + generator.generate(runs));
            return outDir;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write eval suite report to " + outDir, e);
        }
    }

    public static EvalSuiteSummary readJson(Path reportJson) {
        try {
            return MAPPER.readValue(Files.readString(reportJson), EvalSuiteSummary.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + reportJson, e);
        }
    }

    public static String summaryMarkdown(EvalSuiteSummary s) {
        StringBuilder sb = new StringBuilder("# Eval Suite Summary\n\n");
        sb.append("Generated: `").append(s.generatedAt()).append("`\n\n");
        sb.append("| Scenario | Runs | Hard pass | Overall pass | Tool sel. | TTFT p50/p95 (ms) | Total p50/p95 (ms)"
                + " | Tokens in/out | Cost | Judge pass |\n");
        sb.append("|----------|------|-----------|--------------|-----------|-------------------|-------------------"
                + "|---------------|------|------------|\n");
        row(sb, "**ALL**", s.overall());
        for (ScenarioStats sc : s.scenarios()) {
            row(sb, sc.code(), sc.stats());
        }
        Stats o = s.overall();
        if (!o.judgeDimMean().isEmpty()) {
            sb.append("\n### Judge dimension means\n\n| Dimension | Mean |\n|-----------|------|\n");
            o.judgeDimMean().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> sb.append("| ").append(e.getKey()).append(" | ")
                            .append(fmt(e.getValue())).append(" |\n"));
        }
        return sb.toString();
    }

    private static void row(StringBuilder sb, String name, Stats st) {
        sb.append("| ").append(name).append(" | ").append(st.runs())
                .append(" | ").append(pct(st.hardPassRate()))
                .append(" | ").append(pct(st.overallPassRate()))
                .append(" | ").append(pct(st.toolSelectionAccuracy()))
                .append(" | ").append(fmt(st.ttftP50())).append('/').append(fmt(st.ttftP95()))
                .append(" | ").append(fmt(st.totalP50())).append('/').append(fmt(st.totalP95()))
                .append(" | ").append(fmt(st.avgTokensIn())).append('/').append(fmt(st.avgTokensOut()))
                .append(" | ").append(st.costUsd() == null ? "—" : String.format(Locale.ROOT, "%.4f", st.costUsd()))
                .append(" | ").append(pct(st.judgeThresholdPassRate()))
                .append(" |\n");
    }

    private static String pct(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%.0f%%", v * 100);
    }

    private static String fmt(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%.1f", v);
    }
}
