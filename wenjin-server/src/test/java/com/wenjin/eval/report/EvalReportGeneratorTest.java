package com.wenjin.eval.report;

import com.wenjin.eval.hard.HardAssertFailure;
import com.wenjin.eval.hard.HardAssertResult;
import com.wenjin.eval.judge.JudgeResult;
import com.wenjin.eval.judge.JudgeScore;
import com.wenjin.eval.runner.EvalCaseOutcome;
import com.wenjin.eval.runner.EvalMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalReportGeneratorTest {

    private static final Instant FIXED = Instant.parse("2026-07-20T15:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("markdown snapshot: summary table + hard + judge sections")
    void markdownSnapshot() {
        EvalCaseOutcome s01 = EvalCaseOutcome.completed(
                "S01",
                EvalMode.REPLAY,
                HardAssertResult.pass(),
                100L,
                200L,
                "建议先巩固用例图相关薄弱点。",
                List.of("query_mastery"));
        JudgeResult judgeOk = JudgeResult.ok(
                "judge-prompt-v1",
                List.of(
                        new JudgeScore("symptom_fit", 5, "对症"),
                        new JudgeScore("guidance", 4, "引导充分")),
                1);

        EvalCaseOutcome s02 = EvalCaseOutcome.completed(
                "S02",
                EvalMode.LIVE,
                HardAssertResult.fail(List.of(
                        new HardAssertFailure("mustCallTools", "missing query_mastery"))),
                101L,
                201L,
                "直接答案……",
                List.of());
        JudgeResult judgeFail = JudgeResult.failure("judge-prompt-v1", "missing dimensions", 3);

        EvalCaseOutcome skipped = EvalCaseOutcome.skipped("S09", EvalMode.REPLAY, "wenjin.features.eval-enabled=false");

        String md = new EvalReportGenerator(FIXED).generate(List.of(
                EvalReportCase.of(s01, judgeOk),
                EvalReportCase.of(s02, judgeFail),
                EvalReportCase.of(skipped)));

        // Structural snapshot (exact text; blank lines after each section are intentional)
        assertThat(md).contains(
                "# Eval Report",
                "Generated: `2026-07-20T15:00:00Z`",
                "| Scenario | Mode | Skipped | Hard | Judge | Overall |",
                "| S01 | REPLAY | no | PASS | PASS (avg 4.5) | PASS |",
                "| S02 | LIVE | no | FAIL | FAIL | FAIL |",
                "| S09 | REPLAY | yes | — | — | SKIP |",
                "## S01",
                "- **Mode**: REPLAY",
                "- **Hard pass**: true",
                "- **Tools called**: query_mastery",
                "> 建议先巩固用例图相关薄弱点。",
                "### Hard asserts",
                "All hard asserts **passed**.",
                "### Judge scores",
                "- **Prompt version**: `judge-prompt-v1`",
                "| symptom_fit | 5 | 对症 |",
                "| guidance | 4 | 引导充分 |",
                "## S02",
                "| mustCallTools | missing query_mastery |",
                "- **Failure**: missing dimensions",
                "## S09",
                "- **Skipped**: wenjin.features.eval-enabled=false");

        // Full equality against a normalized snapshot (LF)
        String expected = """
                # Eval Report

                Generated: `2026-07-20T15:00:00Z`

                ## Summary

                | Scenario | Mode | Skipped | Hard | Judge | Overall |
                |----------|------|---------|------|-------|--------|
                | S01 | REPLAY | no | PASS | PASS (avg 4.5) | PASS |
                | S02 | LIVE | no | FAIL | FAIL | FAIL |
                | S09 | REPLAY | yes | — | — | SKIP |

                ## S01

                - **Mode**: REPLAY
                - **Hard pass**: true
                - **Run id**: 100
                - **Conversation id**: 200
                - **Tools called**: query_mastery
                - **Final response** (truncated):

                > 建议先巩固用例图相关薄弱点。

                ### Hard asserts

                All hard asserts **passed**.

                ### Judge scores

                - **Prompt version**: `judge-prompt-v1`
                - **Success**: true
                - **Attempts**: 1

                | Dimension | Score | Reason |
                |-----------|-------|--------|
                | symptom_fit | 5 | 对症 |
                | guidance | 4 | 引导充分 |


                ## S02

                - **Mode**: LIVE
                - **Hard pass**: false
                - **Run id**: 101
                - **Conversation id**: 201
                - **Tools called**: _(none)_
                - **Final response** (truncated):

                > 直接答案……

                ### Hard asserts

                | Check | Message |
                |-------|--------|
                | mustCallTools | missing query_mastery |

                ### Judge scores

                - **Prompt version**: `judge-prompt-v1`
                - **Success**: false
                - **Attempts**: 3
                - **Failure**: missing dimensions


                ## S09

                - **Mode**: REPLAY
                - **Skipped**: wenjin.features.eval-enabled=false

                """;
        assertThat(md).isEqualTo(expected);
    }

    @Test
    @DisplayName("threshold enforced in Overall; missing judge is N/A and not a failure")
    void thresholdAndNa() {
        EvalCaseOutcome ok = EvalCaseOutcome.completed(
                "S01", EvalMode.LIVE, HardAssertResult.pass(), 1L, 2L, "a", List.of());
        EvalCaseOutcome ok2 = EvalCaseOutcome.completed(
                "S02", EvalMode.LIVE, HardAssertResult.pass(), 3L, 4L, "b", List.of());
        var dims = List.of(new com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension("guidance", "", 4));
        JudgeResult low = JudgeResult.ok("judge-prompt-v1", List.of(new JudgeScore("guidance", 3, "meh")), 1);

        String md = new EvalReportGenerator(FIXED).generate(List.of(
                EvalReportCase.of(ok, low, dims),
                EvalReportCase.of(ok2)));

        assertThat(md).contains(
                "| S01 | LIVE | no | PASS | FAIL (avg 3.0) | FAIL |",
                "| S02 | LIVE | no | PASS | N/A | PASS |",
                "Judge N/A (not run, not counted as failure): 1",
                "- **Verdict**: FAIL (below threshold: guidance)");
    }

    @Test
    @DisplayName("write to path under temp dir")
    void writeToPath() throws Exception {
        Path out = tempDir.resolve("eval-report.md");
        EvalCaseOutcome o = EvalCaseOutcome.completed(
                "S09", EvalMode.REPLAY, HardAssertResult.pass(), 1L, 2L, "hi", List.of());
        new EvalReportGenerator(FIXED).write(List.of(EvalReportCase.of(o)), out);
        assertThat(Files.exists(out)).isTrue();
        String content = Files.readString(out);
        assertThat(content).contains("# Eval Report");
        assertThat(content).contains("| S09 |");
        assertThat(content).contains("DEFAULT_REPORT".isEmpty() ? "S09" : "S09"); // keep simple
        assertThat(EvalReportGenerator.DEFAULT_REPORT_PATH).isEqualTo("target/eval-report.md");
    }
}
