package com.wenjin.eval.report;

import com.wenjin.eval.hard.HardAssertFailure;
import com.wenjin.eval.hard.HardAssertResult;
import com.wenjin.eval.judge.JudgeResult;
import com.wenjin.eval.judge.JudgeScore;
import com.wenjin.eval.runner.EvalCaseOutcome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Markdown eval report: batch summary table + per-scenario hard assert / judge details.
 * <p>Default write path: {@code target/eval-report.md}.
 */
public final class EvalReportGenerator {

    public static final String DEFAULT_REPORT_PATH = "target/eval-report.md";

    private final Instant generatedAt;

    public EvalReportGenerator() {
        this(Instant.now());
    }

    public EvalReportGenerator(Instant generatedAt) {
        this.generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }

    public String generate(List<EvalReportCase> cases) {
        List<EvalReportCase> list = cases == null ? List.of() : List.copyOf(cases);
        StringBuilder sb = new StringBuilder();
        sb.append("# Eval Report\n\n");
        sb.append("Generated: `").append(generatedAt).append("`\n\n");

        appendSummary(sb, list);
        sb.append("\n");
        for (EvalReportCase c : list) {
            appendScenario(sb, c);
            sb.append("\n");
        }
        return sb.toString();
    }

    public Path write(List<EvalReportCase> cases, Path path) {
        Objects.requireNonNull(path, "path");
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, generate(cases));
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write eval report: " + path, e);
        }
    }

    public Path writeDefault(List<EvalReportCase> cases) {
        return write(cases, Path.of(DEFAULT_REPORT_PATH));
    }

    private static void appendSummary(StringBuilder sb, List<EvalReportCase> cases) {
        sb.append("## Summary\n\n");
        sb.append("| Scenario | Mode | Skipped | Hard | Judge | Overall |\n");
        sb.append("|----------|------|---------|------|-------|--------|\n");
        for (EvalReportCase c : cases) {
            EvalCaseOutcome o = c.outcome();
            String hard = o.skipped() ? "—" : (o.hardPass() ? "PASS" : "FAIL");
            String judge = formatJudgeSummary(c.judge());
            String overall = overallLabel(o, c.judge());
            sb.append("| ")
                    .append(escapeCell(o.scenarioCode())).append(" | ")
                    .append(o.mode()).append(" | ")
                    .append(o.skipped() ? "yes" : "no").append(" | ")
                    .append(hard).append(" | ")
                    .append(judge).append(" | ")
                    .append(overall).append(" |\n");
        }
        if (cases.isEmpty()) {
            sb.append("| _(empty)_ | — | — | — | — | — |\n");
        }
    }

    private static void appendScenario(StringBuilder sb, EvalReportCase c) {
        EvalCaseOutcome o = c.outcome();
        sb.append("## ").append(o.scenarioCode()).append("\n\n");
        sb.append("- **Mode**: ").append(o.mode()).append('\n');
        if (o.skipped()) {
            sb.append("- **Skipped**: ").append(nullToDash(o.skipReason())).append('\n');
            return;
        }
        sb.append("- **Hard pass**: ").append(o.hardPass()).append('\n');
        sb.append("- **Run id**: ").append(o.runId() == null ? "—" : o.runId()).append('\n');
        sb.append("- **Conversation id**: ")
                .append(o.conversationId() == null ? "—" : o.conversationId()).append('\n');
        sb.append("- **Tools called**: ")
                .append(o.toolsCalled().isEmpty() ? "_(none)_" : String.join(", ", o.toolsCalled()))
                .append('\n');
        if (o.finalResponse() != null && !o.finalResponse().isBlank()) {
            sb.append("- **Final response** (truncated):\n\n");
            sb.append("> ").append(truncate(o.finalResponse(), 240).replace("\n", "\n> ")).append("\n\n");
        }

        sb.append("### Hard asserts\n\n");
        HardAssertResult hard = o.hardResult();
        if (hard == null) {
            sb.append("_(no hard result)_\n\n");
        } else if (hard.passed()) {
            sb.append("All hard asserts **passed**.\n\n");
        } else {
            sb.append("| Check | Message |\n");
            sb.append("|-------|--------|\n");
            for (HardAssertFailure f : hard.failures()) {
                sb.append("| ").append(escapeCell(f.check())).append(" | ")
                        .append(escapeCell(f.message())).append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("### Judge scores\n\n");
        JudgeResult judge = c.judge();
        if (judge == null) {
            sb.append("_(judge not run)_\n\n");
        } else {
            sb.append("- **Prompt version**: `").append(judge.promptVersion()).append("`\n");
            sb.append("- **Success**: ").append(judge.success()).append('\n');
            sb.append("- **Attempts**: ").append(judge.attempts()).append('\n');
            if (!judge.success()) {
                sb.append("- **Failure**: ").append(nullToDash(judge.failureReason())).append("\n\n");
            } else {
                sb.append('\n');
                sb.append("| Dimension | Score | Reason |\n");
                sb.append("|-----------|-------|--------|\n");
                for (JudgeScore s : judge.scores()) {
                    sb.append("| ").append(escapeCell(s.key())).append(" | ")
                            .append(s.score()).append(" | ")
                            .append(escapeCell(s.reason())).append(" |\n");
                }
                sb.append('\n');
            }
        }
    }

    private static String formatJudgeSummary(JudgeResult judge) {
        if (judge == null) {
            return "—";
        }
        if (!judge.success()) {
            return "FAIL";
        }
        if (judge.scores().isEmpty()) {
            return "PASS";
        }
        double avg = judge.scores().stream().mapToInt(JudgeScore::score).average().orElse(0);
        return String.format("PASS (avg %.1f)", avg);
    }

    private static String overallLabel(EvalCaseOutcome o, JudgeResult judge) {
        if (o.skipped()) {
            return "SKIP";
        }
        boolean hardOk = o.hardPass();
        boolean judgeOk = judge == null || judge.success();
        // When judge ran and failed contract → not overall pass; score thresholds are suite policy later.
        if (hardOk && judgeOk) {
            return "PASS";
        }
        return "FAIL";
    }

    private static String escapeCell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\n", " ");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }
}
