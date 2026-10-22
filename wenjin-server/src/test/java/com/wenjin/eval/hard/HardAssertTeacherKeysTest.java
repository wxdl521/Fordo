package com.wenjin.eval.hard;

import com.wenjin.eval.scenario.EvalScenarioDocument.HardAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Hard keys {@code replyMustContain} and {@code guardCodesExpected} (teacher-assistant scenarios). */
class HardAssertTeacherKeysTest {

    private final HardAssertChecker checker = new HardAssertChecker();

    private HardAssertResult run(String reply, List<EvalStepView> steps, List<String> contain, List<String> guards) {
        HardAssertions hard = new HardAssertions(List.of(), List.of(), null, null, null, null, null, null,
                contain, guards);
        return checker.check(new HardAssertInput(steps, reply, hard, Set.of()));
    }

    private static EvalStepView guard(String name, String errorCode) {
        return new EvalStepView("GUARD", name, 2, "{}", "{}", 1, 0, 0, 1L, errorCode);
    }

    @Test
    void replyMustContain_passesWhenAllPresent() {
        assertThat(run("赵强和周婷需要关注", List.of(), List.of("赵强", "周婷"), null).passed()).isTrue();
    }

    @Test
    void replyMustContain_failsOnMissingText() {
        HardAssertResult r = run("没有人需要关注", List.of(), List.of("赵强"), null);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).extracting(HardAssertFailure::check).contains("replyMustContain");
    }

    @Test
    void guardCodesExpected_passesWhenGuardStepHasCode() {
        assertThat(run("无法提供", List.of(guard("recall_student_context", "TOOL_NOT_ALLOWED")), null,
                List.of("TOOL_NOT_ALLOWED")).passed()).isTrue();
    }

    @Test
    void guardCodesExpected_failsWithoutMatchingGuardStep() {
        HardAssertResult none = run("无法提供", List.of(), null, List.of("TOOL_NOT_ALLOWED"));
        assertThat(none.passed()).isFalse();
        assertThat(none.failures()).extracting(HardAssertFailure::check).contains("guardCodesExpected");
        HardAssertResult other = run("x", List.of(guard("a", "TOOL_BUDGET_EXCEEDED")), null,
                List.of("TOOL_NOT_ALLOWED"));
        assertThat(other.passed()).isFalse();
    }

    @Test
    void nullOrEmptyIsIgnored() {
        assertThat(run("任意", List.of(), null, null).passed()).isTrue();
        assertThat(run("任意", List.of(), List.of(), List.of()).passed()).isTrue();
    }
}
