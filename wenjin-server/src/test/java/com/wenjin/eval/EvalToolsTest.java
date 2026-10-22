package com.wenjin.eval;

import com.wenjin.eval.calibration.JudgeCalibrationScorer;
import com.wenjin.eval.report.EvalReportComparator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Command-line style entry points for eval report tooling (opt-in via system properties, skipped otherwise).
 * <pre>
 * mvnw test -Dtest=EvalToolsTest -Dwenjin.eval.compare=target/eval-live/A/report.json,target/eval-live/B/report.json
 *   → prints and writes target/eval-compare.md
 * mvnw test -Dtest=EvalToolsTest -Dwenjin.eval.calibration-csv=target/eval-live/B/calibration.csv
 *   → prints agreement rate (|human−judge| ≤ 1) and per-dimension bias for the filled-in human_score column
 * </pre>
 */
class EvalToolsTest {

    @Test
    @EnabledIfSystemProperty(named = "wenjin.eval.compare", matches = ".+,.+")
    @DisplayName("compare two report.json files → target/eval-compare.md")
    void compareReports() throws Exception {
        String[] paths = System.getProperty("wenjin.eval.compare").split(",");
        String markdown = EvalReportComparator.compare(Path.of(paths[0].trim()), Path.of(paths[1].trim()));
        Path out = Path.of("target", "eval-compare.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, markdown, StandardCharsets.UTF_8);
        System.out.println(markdown);
        System.out.println("written: " + out.toAbsolutePath());
        assertThat(markdown).isNotBlank();
    }

    @Test
    @EnabledIfSystemProperty(named = "wenjin.eval.calibration-csv", matches = ".+")
    @DisplayName("score a human-labelled calibration.csv")
    void scoreCalibration() {
        JudgeCalibrationScorer.Report report =
                JudgeCalibrationScorer.score(Path.of(System.getProperty("wenjin.eval.calibration-csv")));
        System.out.println("labelled=" + report.labelled() + " agreed=" + report.agreed()
                + " agreementRate=" + report.agreementRate() + " biasByDimension=" + report.biasByDimension());
        assertThat(report.labelled()).as("rows with human_score").isPositive();
    }
}
