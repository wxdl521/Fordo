package com.wenjin.eval.metrics;

import com.wenjin.eval.calibration.JudgeCalibrationExporter;
import com.wenjin.eval.calibration.JudgeCalibrationScorer;
import com.wenjin.eval.hard.EvalStepView;
import com.wenjin.eval.hard.HardAssertResult;
import com.wenjin.eval.judge.JudgeResult;
import com.wenjin.eval.judge.JudgeScore;
import com.wenjin.eval.metrics.EvalSuiteAggregator.ToolExpectation;
import com.wenjin.eval.report.EvalReportCase;
import com.wenjin.eval.report.EvalReportComparator;
import com.wenjin.eval.report.EvalSuiteReportWriter;
import com.wenjin.eval.report.EvalReportGenerator;
import com.wenjin.eval.runner.EvalCaseOutcome;
import com.wenjin.eval.runner.EvalMode;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvalSuiteAggregatorTest {

    private static final List<JudgeDimension> DIMS = List.of(new JudgeDimension("g", "", 4));
    private static final Instant T = Instant.parse("2026-10-21T00:00:00Z");

    @TempDir
    Path tmp;

    private static EvalReportCase run(String code, boolean hard, List<String> tools,
                                      Long ttft, Long total, long in, long out, Integer judgeScore) {
        EvalCaseMetrics m = new EvalCaseMetrics(ttft, total, 1, tools.size(), in, out, false, null);
        EvalCaseOutcome o = EvalCaseOutcome.completed(code, EvalMode.LIVE,
                hard ? HardAssertResult.pass() : HardAssertResult.fail(List.of(
                        new com.wenjin.eval.hard.HardAssertFailure("x", "y"))),
                1L, 2L, "answer", tools, m);
        JudgeResult j = judgeScore == null ? null
                : JudgeResult.ok("v", List.of(new JudgeScore("g", judgeScore, "r")), 1);
        return EvalReportCase.of(o, j, DIMS);
    }

    private EvalSuiteSummary sample() {
        List<EvalReportCase> runs = List.of(
                run("S01", true, List.of("query_mastery"), 100L, 1000L, 1000, 200, 5),
                run("S01", true, List.of("query_mastery"), 200L, 2000L, 1000, 200, 3),
                run("S01", false, List.of(), 300L, 3000L, 1000, 200, null),
                run("S01", true, List.of("query_mastery", "start_practice"), 400L, 4000L, 1000, 200, 4));
        Map<String, ToolExpectation> exp = Map.of("S01",
                new ToolExpectation(List.of("query_mastery"), List.of("start_practice")));
        EvalProperties.Price price = new EvalProperties.Price();
        price.setInputPerMtok(2);
        price.setOutputPerMtok(10);
        return EvalSuiteAggregator.aggregate(runs, exp, price, T);
    }

    @Test
    void aggregatesRates() {
        EvalSuiteSummary.Stats s = sample().overall();
        assertThat(s.runs()).isEqualTo(4);
        assertThat(s.hardPassRate()).isEqualTo(0.75);
        // run2 judge 3 < 4 fails threshold -> overall pass = runs 1 and 4 = 2/4
        assertThat(s.overallPassRate()).isEqualTo(0.5);
        // run1 ok, run2 ok, run3 missing call, run4 calls mustNot
        assertThat(s.toolSelectionAccuracy()).isEqualTo(0.5);
        assertThat(s.ttftP50()).isEqualTo(200.0);
        assertThat(s.ttftP95()).isEqualTo(400.0);
        assertThat(s.totalP50()).isEqualTo(2000.0);
        assertThat(s.avgTokensIn()).isEqualTo(1000.0);
        assertThat(s.costUsd()).isCloseTo(4000 / 1e6 * 2 + 800 / 1e6 * 10, within(1e-9));
        assertThat(s.judgeRuns()).isEqualTo(3);
        assertThat(s.judgeDimMean().get("g")).isEqualTo(4.0);
        assertThat(s.judgeThresholdPassRate()).isCloseTo(2.0 / 3, within(1e-9));
    }

    @Test
    void zeroPriceMeansNoCost() {
        EvalSuiteSummary s = EvalSuiteAggregator.aggregate(
                List.of(run("S01", true, List.of(), 1L, 2L, 10, 10, null)), Map.of(),
                new EvalProperties.Price(), T);
        assertThat(s.overall().costUsd()).isNull();
        assertThat(s.overall().judgeThresholdPassRate()).isNull();
        assertThat(s.overall().toolSelectionAccuracy()).isNull();
    }

    @Test
    void percentileNearestRank() {
        assertThat(EvalSuiteAggregator.percentile(List.of(), 50)).isNull();
        assertThat(EvalSuiteAggregator.percentile(List.of(5.0), 95)).isEqualTo(5.0);
        assertThat(EvalSuiteAggregator.percentile(List.of(4.0, 1.0, 3.0, 2.0), 50)).isEqualTo(2.0);
    }

    @Test
    void metricsFromSteps() {
        List<EvalStepView> steps = List.of(
                new EvalStepView("LLM_CALL", "llm_round", 1, null, null, 1, 100, 20, 50L, null),
                new EvalStepView("TOOL_CALL", "query_mastery", 1, null, null),
                new EvalStepView("LLM_CALL", "llm_round", 1, null, null, 3, 150, 30, 60L, null));
        EvalCaseMetrics m = EvalCaseMetrics.fromSteps(steps, 10L, 99L);
        assertThat(m.llmCalls()).isEqualTo(2);
        assertThat(m.toolCalls()).isEqualTo(1);
        assertThat(m.tokensIn()).isEqualTo(250);
        assertThat(m.tokensOut()).isEqualTo(50);
        assertThat(m.tokensEstimated()).isFalse();
    }

    @Test
    void writesAndComparesReports() throws Exception {
        EvalSuiteSummary base = sample();
        List<EvalReportCase> good = List.of(run("S01", true, List.of("query_mastery"), 50L, 500L, 800, 100, 5),
                run("S02", true, List.of(), 50L, 500L, 800, 100, 5));
        Path dir = new EvalSuiteReportWriter(new EvalReportGenerator(T))
                .write(tmp.resolve("a"), good, EvalSuiteAggregator.aggregate(good, Map.of(), null, T));
        assertThat(Files.readString(dir.resolve("report.md"))).contains("Eval Suite Summary", "S01");
        EvalSuiteSummary roundTrip = EvalSuiteReportWriter.readJson(dir.resolve("report.json"));
        assertThat(roundTrip.scenarios()).hasSize(2);

        List<EvalReportCase> bad = List.of(run("S01", true, List.of(), 90L, 900L, 800, 100, 5),
                run("S02", false, List.of(), 90L, 900L, 800, 100, 5));
        EvalSuiteSummary cand = EvalSuiteAggregator.aggregate(bad, Map.of(), null, T);
        String md = EvalReportComparator.compare(roundTrip, cand);
        assertThat(md).contains("Hard pass rate", "New failing scenarios", "S02");
        assertThat(md).doesNotContain("- S01 (");
        assertThat(EvalReportComparator.compare(base, base)).contains("_(none)_");
    }

    @Test
    void calibrationExportAndScore() {
        List<EvalReportCase> runs = List.of(run("S01", true, List.of(), 1L, 1L, 1, 1, 4),
                run("S02", true, List.of(), 1L, 1L, 1, 1, 5),
                run("S03", true, List.of(), 1L, 1L, 1, 1, 5));
        String csv = JudgeCalibrationExporter.toCsv(runs);
        assertThat(csv).startsWith(JudgeCalibrationExporter.HEADER);
        assertThat(csv.lines().skip(1).filter(l -> l.endsWith(","))).hasSize(3);

        String[] lines = csv.split("\n");
        // judge 4 / human 3 -> agree; judge 5 / human 2 -> disagree; third unlabeled
        String labelled = lines[0] + "\n" + lines[1] + "3\n" + lines[2] + "2\n" + lines[3] + "\n";
        JudgeCalibrationScorer.Report rep = JudgeCalibrationScorer.score(labelled);
        assertThat(rep.labelled()).isEqualTo(2);
        assertThat(rep.agreed()).isEqualTo(1);
        assertThat(rep.agreementRate()).isEqualTo(0.5);
        assertThat(rep.biasByDimension().get("g")).isEqualTo(2.0);
    }
}
