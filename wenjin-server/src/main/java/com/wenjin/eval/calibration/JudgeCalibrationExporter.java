package com.wenjin.eval.calibration;

import com.wenjin.eval.judge.JudgeScore;
import com.wenjin.eval.report.EvalReportCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Exports judged cases to CSV for human labelling. One row per (case, dimension); the
 * {@code human_score} column is left empty for the labeller to fill with 1-5.
 */
public final class JudgeCalibrationExporter {

    public static final String HEADER = "scenario,run_id,dimension,transcript,judge_score,judge_reason,human_score";

    private JudgeCalibrationExporter() {
    }

    public static String toCsv(List<EvalReportCase> cases) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (EvalReportCase c : cases == null ? List.<EvalReportCase>of() : cases) {
            if (c.outcome().skipped() || c.judge() == null || !c.judge().success()) {
                continue;
            }
            for (JudgeScore s : c.judge().scores()) {
                sb.append(CalibrationCsv.field(c.outcome().scenarioCode())).append(',')
                        .append(c.outcome().runId() == null ? "" : c.outcome().runId()).append(',')
                        .append(CalibrationCsv.field(s.key())).append(',')
                        .append(CalibrationCsv.field(c.outcome().finalResponse())).append(',')
                        .append(s.score()).append(',')
                        .append(CalibrationCsv.field(s.reason())).append(',')
                        .append('\n');
            }
        }
        return sb.toString();
    }

    public static Path export(List<EvalReportCase> cases, Path csvPath) {
        try {
            Path parent = csvPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(csvPath, toCsv(cases), StandardCharsets.UTF_8);
            return csvPath;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write calibration CSV " + csvPath, e);
        }
    }
}
