package com.wenjin.eval.runner;

import com.wenjin.eval.calibration.JudgeCalibrationExporter;
import com.wenjin.eval.judge.JudgeRequest;
import com.wenjin.eval.judge.JudgeResult;
import com.wenjin.eval.judge.JudgeService;
import com.wenjin.eval.metrics.EvalCaseMetrics;
import com.wenjin.eval.metrics.EvalProperties;
import com.wenjin.eval.metrics.EvalSuiteAggregator;
import com.wenjin.eval.metrics.EvalSuiteAggregator.ToolExpectation;
import com.wenjin.eval.metrics.EvalSuiteSummary;
import com.wenjin.eval.report.EvalReportCase;
import com.wenjin.eval.report.EvalReportGenerator;
import com.wenjin.eval.report.EvalSuiteReportWriter;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.EvalScenarioStatus;
import com.wenjin.eval.scenario.ScenarioLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Suite driver for LIVE (and REPLAY) runs: selects scenarios, repeats each N times, optionally judges each
 * run, aggregates with {@link EvalSuiteAggregator} and writes {@code report.md} / {@code report.json}
 * (plus {@code calibration.csv} when judged) via {@link EvalSuiteReportWriter}.
 */
public final class LiveEvalSuite {

    private static final Logger log = LoggerFactory.getLogger(LiveEvalSuite.class);

    /**
     * @param scenarioCodes explicit codes (any status, PENDING included); empty = all ENABLED scenarios
     * @param repeat        runs per scenario (≥1)
     * @param judge         judge service, null = no judging (N/A in the report)
     * @param outBaseDir    reports go to {@code outBaseDir/<yyyyMMdd-HHmmss>/}
     */
    public record Options(List<String> scenarioCodes, int repeat, JudgeService judge, Path outBaseDir) {
        public Options {
            scenarioCodes = scenarioCodes == null ? List.of() : List.copyOf(scenarioCodes);
            repeat = Math.max(1, repeat);
            outBaseDir = outBaseDir == null ? Path.of(EvalSuiteReportWriter.DEFAULT_BASE_DIR) : outBaseDir;
        }
    }

    public record Result(List<ScenarioRun> runs, List<EvalReportCase> cases, EvalSuiteSummary summary, Path outDir) {}

    private final ScenarioLoader scenarioLoader;
    private final Function<EvalScenarioDocument, ScenarioRun> executor;
    private final EvalProperties.Price price;

    public LiveEvalSuite(ScenarioLoader scenarioLoader,
                         Function<EvalScenarioDocument, ScenarioRun> executor,
                         EvalProperties.Price price) {
        this.scenarioLoader = Objects.requireNonNull(scenarioLoader, "scenarioLoader");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.price = price;
    }

    /** ENABLED scenarios, or exactly the requested codes (PENDING allowed when listed explicitly). */
    public List<EvalScenarioDocument> select(List<String> codes) {
        List<EvalScenarioDocument> all = scenarioLoader.loadAll();
        if (codes == null || codes.isEmpty()) {
            return all.stream().filter(d -> d.resolvedStatus() == EvalScenarioStatus.ENABLED).toList();
        }
        Map<String, EvalScenarioDocument> byCode = new LinkedHashMap<>();
        all.forEach(d -> byCode.put(d.code(), d));
        List<EvalScenarioDocument> out = new ArrayList<>();
        for (String code : codes) {
            EvalScenarioDocument doc = byCode.get(code.trim());
            if (doc == null) {
                throw new IllegalArgumentException("Unknown scenario: " + code);
            }
            out.add(doc);
        }
        return out;
    }

    public Result run(Options options) {
        Objects.requireNonNull(options, "options");
        List<EvalScenarioDocument> scenarios = select(options.scenarioCodes());
        List<ScenarioRun> runs = new ArrayList<>();
        List<EvalReportCase> cases = new ArrayList<>();
        Map<String, ToolExpectation> expectations = new LinkedHashMap<>();
        for (EvalScenarioDocument scenario : scenarios) {
            expectations.put(scenario.code(), ToolExpectation.fromScenario(scenario));
            List<com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension> dims =
                    scenario.judge() == null ? List.of() : scenario.judge().dimensions();
            for (int i = 1; i <= options.repeat(); i++) {
                ScenarioRun run = executor.apply(scenario);
                EvalCaseOutcome outcome = run.outcome();
                if (i == 1 && outcome.mode() == EvalMode.LIVE) {
                    expectations.put(scenario.code(), ToolExpectation.fromScenario(scenario, true));
                }
                JudgeResult judged = null;
                if (options.judge() != null && !outcome.skipped()) {
                    judged = options.judge().judge(new JudgeRequest(
                            run.transcript(),
                            String.join(", ", outcome.toolsCalled()),
                            scenario.persona() == null ? "" : scenario.persona().note(),
                            dims));
                    EvalCaseMetrics m = outcome.metrics() == null
                            ? EvalCaseMetrics.fromSteps(List.of(), null, null)
                            : outcome.metrics();
                    outcome = outcome.withMetrics(m.withJudge(judged));
                    run = new ScenarioRun(outcome, run.exchanges(), run.actor());
                }
                runs.add(run);
                cases.add(EvalReportCase.of(outcome, judged, dims));
                log.info("eval {} #{}: hardPass={} failures={}", scenario.code(), i, outcome.hardPass(),
                        outcome.hardResult() == null ? List.of() : outcome.hardResult().failures());
            }
        }
        EvalSuiteSummary summary = EvalSuiteAggregator.aggregate(cases, expectations, price, Instant.now());
        Path outDir = EvalSuiteReportWriter.defaultOutDir(options.outBaseDir(), LocalDateTime.now());
        new EvalSuiteReportWriter(new EvalReportGenerator()).write(outDir, cases, summary);
        if (options.judge() != null) {
            JudgeCalibrationExporter.export(cases, outDir.resolve("calibration.csv"));
        }
        log.info("eval report written to {}", outDir.toAbsolutePath());
        return new Result(runs, cases, summary, outDir);
    }
}
