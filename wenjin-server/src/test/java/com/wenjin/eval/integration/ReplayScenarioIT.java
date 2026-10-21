package com.wenjin.eval.integration;

import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.hard.EvalStepView;
import com.wenjin.eval.report.EvalReportCase;
import com.wenjin.eval.report.EvalReportGenerator;
import com.wenjin.eval.runner.EvalHarness;
import com.wenjin.eval.runner.ScenarioRun;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.EvalScenarioStatus;
import com.wenjin.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real REPLAY: every ENABLED scenario runs through the production {@code CompanionServiceImpl} →
 * {@code CompanionOrchestrator} → real tools against the eval fixture DB, with the LLM replaced by the
 * scenario golden (v2). Hard asserts are checked on the {@code agent_step} rows the run actually wrote —
 * this is the orchestration regression test. Writes {@code target/eval-report.md}.
 * <p>Gated by {@code WENJIN_EVAL_IT=true}; DB = Testcontainers or {@code -Dwenjin.eval.jdbc-url}.
 */
@EnabledIfEnvironmentVariable(named = "WENJIN_EVAL_IT", matches = "true")
class ReplayScenarioIT extends EvalDbSupport {

    private static final List<EvalReportCase> CASES = new CopyOnWriteArrayList<>();

    @Autowired
    private EvalHarness harness;

    @Autowired
    private JdbcTemplate jdbc;

    static Stream<String> enabledScenarioCodes() {
        return new ScenarioLoader().loadAll().stream()
                .filter(d -> d.resolvedStatus() == EvalScenarioStatus.ENABLED)
                .map(EvalScenarioDocument::code);
    }

    @ParameterizedTest(name = "REPLAY {0}")
    @MethodSource("enabledScenarioCodes")
    @DisplayName("ENABLED scenario replays through the real orchestrator and passes hard asserts")
    void scenarioReplays(String code) {
        EvalScenarioDocument scenario = harness.scenarioLoader().loadByCode(code).orElseThrow();
        ScenarioRun run = harness.replayExecutor().runDetailed(scenario);
        CASES.add(EvalReportCase.of(run.outcome(), null,
                scenario.judge() == null ? List.of() : scenario.judge().dimensions()));

        assertThat(run.outcome().hardPass())
                .as("%s failures=%s%ntranscript:%n%s", code, run.outcome().hardResult().failures(), run.transcript())
                .isTrue();
        assertThat(run.outcome().runId()).as("agent_run written").isNotNull();
        assertThat(run.outcome().metrics().llmCalls()).as("llm_round steps recorded").isGreaterThanOrEqualTo(
                scenario.liveFaults() != null && scenario.liveFaults().llmFailures() != null ? 0 : 1);
    }

    @Test
    @DisplayName("S07 fault injection degrades: run status DEGRADED, DEGRADE step, template reply")
    void s07_degrades() {
        EvalScenarioDocument s07 = harness.scenarioLoader().loadByCode("S07").orElseThrow();
        ScenarioRun run = harness.replayExecutor().runDetailed(s07);
        assertThat(run.outcome().hardPass()).as("%s", run.outcome().hardResult().failures()).isTrue();
        Integer status = jdbc.queryForObject("SELECT status FROM agent_run WHERE id = ?", Integer.class,
                run.outcome().runId());
        assertThat(status).isEqualTo(3); // DEGRADED
        assertThat(run.outcome().finalResponse()).isNotBlank();
    }

    @Test
    @DisplayName("S06 budget override: second start_practice is GUARDed, tool budget restored afterwards")
    void s06_budgetExhausted() {
        EvalScenarioDocument s06 = harness.scenarioLoader().loadByCode("S06").orElseThrow();
        ScenarioRun run = harness.replayExecutor().runDetailed(s06);
        assertThat(run.outcome().hardPass()).as("%s", run.outcome().hardResult().failures()).isTrue();
        List<String> guards = jdbc.queryForList(
                "SELECT error_code FROM agent_step WHERE run_id = ? AND step_type = 'GUARD'", String.class,
                run.outcome().runId());
        assertThat(guards).contains("TOOL_BUDGET_EXCEEDED");
        Integer practiceSessions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM practice_session WHERE student_id = ?", Integer.class,
                harness.fixture().world().studentId());
        assertThat(practiceSessions).isEqualTo(1);
    }

    @Test
    @DisplayName("a golden without the required tool round fails mustCallTools and reports replay drift")
    void brokenGolden_fails() {
        EvalScenarioDocument s04 = harness.scenarioLoader().loadByCode("S04").orElseThrow();
        GoldenFile original = harness.goldenLoader().loadFromClasspath("S04");
        GoldenFile.GoldenRound answerOnly = original.turns().get(0).rounds().get(1);
        GoldenFile broken = new GoldenFile("S04", 2, "", "", true, List.of(
                new GoldenFile.GoldenTurn(List.of(answerOnly, answerOnly))));

        ScenarioRun run = harness.replayExecutor(code -> broken).runDetailed(s04);

        assertThat(run.outcome().hardPass()).isFalse();
        assertThat(run.outcome().hardResult().failures())
                .anyMatch(f -> "mustCallTools".equals(f.check()))
                .anyMatch(f -> "replay".equals(f.check()) && f.message().contains("need re-record"));
    }

    @Test
    @DisplayName("readback carries llm_round metrics (seq, tokens, duration)")
    void stepsCarryMetrics() {
        EvalScenarioDocument s04 = harness.scenarioLoader().loadByCode("S04").orElseThrow();
        ScenarioRun run = harness.replayExecutor().runDetailed(s04);
        List<EvalStepView> steps = new ArrayList<>();
        steps.addAll(jdbc.query("SELECT step_type, name, seq, tokens_in FROM agent_step WHERE run_id = ? ORDER BY seq",
                (rs, i) -> new EvalStepView(rs.getString(1), rs.getString(2), 0, null, null,
                        rs.getInt(3), rs.getInt(4), null, null, null), run.outcome().runId()));
        assertThat(steps).extracting(EvalStepView::name).containsSubsequence("llm_round", "query_mastery", "llm_round");
        assertThat(run.outcome().metrics().llmCalls()).isEqualTo(2);
        assertThat(run.outcome().metrics().toolCalls()).isEqualTo(1);
        assertThat(run.outcome().metrics().totalMs()).isNotNull();
        assertThat(run.outcome().metrics().ttftMs()).as("content streamed in chunks").isNotNull();
    }

    @AfterAll
    static void writeReport() {
        List<EvalReportCase> cases = new ArrayList<>(CASES);
        cases.sort((a, b) -> a.outcome().scenarioCode().compareTo(b.outcome().scenarioCode()));
        if (!cases.isEmpty()) {
            new EvalReportGenerator().writeDefault(cases);
        }
    }
}
