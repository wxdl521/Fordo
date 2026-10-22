package com.wenjin.eval.runner;

import com.wenjin.eval.hard.HardAssertChecker;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioDriverTest {

    @Test
    @DisplayName("turn 抛出 Error（非 RuntimeException）时 afterScenario 仍被调用，共享覆盖得以恢复")
    void afterScenarioRunsEvenWhenTurnThrowsError() {
        EvalScenarioDocument scenario = new ScenarioLoader().loadByCode("S09").orElseThrow();
        AtomicInteger before = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();
        ScenarioLifecycle hooks = new ScenarioLifecycle() {
            @Override
            public void beforeScenario(EvalScenarioDocument s) {
                before.incrementAndGet();
            }

            @Override
            public EvalCaseOutcome afterScenario(EvalScenarioDocument s, EvalCaseOutcome outcome) {
                after.incrementAndGet();
                return outcome;
            }
        };

        ScenarioRun run = ScenarioDriver.drive(EvalMode.REPLAY, scenario,
                request -> {
                    throw new AssertionError("golden mismatch");
                },
                new HardAssertChecker(), null, hooks);

        assertThat(before.get()).isEqualTo(1);
        assertThat(after.get()).isEqualTo(1);
        assertThat(run.outcome().hardPass()).isFalse();
        assertThat(run.outcome().hardResult().failures())
                .anySatisfy(f -> assertThat(f.toString()).contains("golden mismatch"));
    }

    @Test
    @DisplayName("教师场景：请求带 actor=teacher；学生场景保持 student")
    void turnRequestCarriesActor() {
        java.util.List<CompanionTurnRunner.TurnRequest> seen = new java.util.ArrayList<>();
        CompanionTurnRunner recorder = request -> {
            seen.add(request);
            return new CompanionTurnRunner.TurnResult(1L, 1L,
                    new com.wenjin.eval.collect.CollectedReply("ok", java.util.List.of(), java.util.List.of()),
                    java.util.List.of());
        };

        ScenarioRun teacher = ScenarioDriver.drive(EvalMode.REPLAY, teacherScenario(), recorder,
                new HardAssertChecker(), null, null);
        ScenarioDriver.drive(EvalMode.REPLAY, new ScenarioLoader().loadByCode("S09").orElseThrow(), recorder,
                new HardAssertChecker(), null, null);

        assertThat(seen).extracting(CompanionTurnRunner.TurnRequest::actor).containsExactly("teacher", "student");
        assertThat(seen.get(0).isTeacher()).isTrue();
        assertThat(teacher.actor()).isEqualTo("teacher");
        assertThat(teacher.transcript()).contains("老师：").contains("AI 助教：").doesNotContain("学生：");
    }

    @Test
    @DisplayName("被治理层拦截（TOOL_NOT_ALLOWED）的越权工具不计入 toolsCalled，但 GUARD 步仍计入工具预算")
    void blockedToolIsNotCountedAsCalled() {
        EvalScenarioDocument scenario = teacherScenario();
        CompanionTurnRunner runner = request -> new CompanionTurnRunner.TurnResult(1L, 1L,
                new com.wenjin.eval.collect.CollectedReply("无法提供",
                        java.util.List.of(), java.util.List.of(
                        new com.wenjin.eval.collect.CollectedReply.ToolCallEvent("recall_student_context", "x", true),
                        new com.wenjin.eval.collect.CollectedReply.ToolCallEvent("get_student_profile", "y", true))),
                java.util.List.of(
                        new com.wenjin.eval.hard.EvalStepView("GUARD", "recall_student_context", 2, "{}", "{}", 1, 0, 0,
                                1L, "TOOL_NOT_ALLOWED"),
                        new com.wenjin.eval.hard.EvalStepView("TOOL_CALL", "get_student_profile", 1, "{}", "{}", 2, 0,
                                0, 1L, null)));

        ScenarioRun run = ScenarioDriver.drive(EvalMode.REPLAY, scenario, runner, new HardAssertChecker(), null, null);

        assertThat(run.outcome().toolsCalled()).containsExactly("get_student_profile");
        assertThat(run.exchanges().get(0).tools()).containsExactly("get_student_profile");
    }

    private static EvalScenarioDocument teacherScenario() {
        return new EvalScenarioDocument("T90", "t",
                new EvalScenarioDocument.Persona(java.util.List.of(), java.util.List.of(), "n"),
                java.util.List.of(new EvalScenarioDocument.ScriptTurn("hi")),
                new EvalScenarioDocument.HardAssertions(java.util.List.of(), java.util.List.of("recall_student_context"),
                        3, null, null, null),
                new EvalScenarioDocument.JudgeSpec(java.util.List.of(
                        new EvalScenarioDocument.JudgeDimension("expression", "x", 3))),
                "ENABLED", null, null, "teacher");
    }
}
