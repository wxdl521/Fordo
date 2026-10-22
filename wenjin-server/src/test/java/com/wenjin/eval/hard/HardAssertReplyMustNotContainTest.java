package com.wenjin.eval.hard;

import com.wenjin.eval.scenario.EvalScenarioDocument.HardAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Hard key {@code replyMustNotContain}: the final reply must not echo the listed substrings (e.g. source lines). */
class HardAssertReplyMustNotContainTest {

    private final HardAssertChecker checker = new HardAssertChecker();

    private HardAssertResult run(String reply, List<String> forbidden) {
        HardAssertions hard = new HardAssertions(List.of(), List.of(), null, null, null, null, null, forbidden);
        return checker.check(new HardAssertInput(List.of(), reply, hard, Set.of()));
    }

    @Test
    void passesWhenReplyHasNoForbiddenText() {
        assertThat(run("缺少分号", List.of("System.out.println(total);")).passed()).isTrue();
    }

    @Test
    void failsWhenReplyEchoesForbiddenText() {
        HardAssertResult r = run("你写的 System.out.println(total); 没问题", List.of("System.out.println(total);"));
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).extracting(HardAssertFailure::check).contains("replyMustNotContain");
    }

    @Test
    void nullOrEmptyListIsIgnored() {
        assertThat(run("任意回复", null).passed()).isTrue();
        assertThat(run("任意回复", List.of()).passed()).isTrue();
    }
}
