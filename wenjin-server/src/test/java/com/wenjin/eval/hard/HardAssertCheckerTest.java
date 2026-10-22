package com.wenjin.eval.hard;

import com.wenjin.eval.scenario.EvalScenarioDocument.HardAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Table-driven hard assert cases (≥12 groups).
 */
class HardAssertCheckerTest {

    private final HardAssertChecker checker = new HardAssertChecker();

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("HardAssertChecker table-driven")
    void table(Case c) {
        HardAssertResult result = checker.check(new HardAssertInput(
                c.steps(), c.response(), c.hard(), c.whitelist()));
        assertThat(result.passed())
                .as("failures=%s", result.failures())
                .isEqualTo(c.expectPass());
        if (c.expectedFailureCheck() != null) {
            assertThat(result.failures())
                    .extracting(HardAssertFailure::check)
                    .contains(c.expectedFailureCheck());
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                // 1 pass: happy path S09-like
                args("pass_empty_tools_nonempty_reply",
                        steps(llm("phase2", "hello")),
                        "你好，可以这样学",
                        hard(List.of(), List.of(), 6, true, true, false),
                        Set.of("KT01", "SE-3.2"),
                        true, null),

                // 2 fail: empty response
                args("fail_empty_response",
                        steps(llm("phase2", "")),
                        "  ",
                        hard(List.of(), List.of(), 6, true, true, false),
                        Set.of(),
                        false, "nonEmptyResponse"),

                // 3 fail: mustCallTools missing
                args("fail_must_call_missing",
                        steps(llm("phase1", "{}"), llm("phase2", "ok")),
                        "ok reply",
                        hard(List.of("query_mastery"), List.of(), 4, true, true, false),
                        Set.of(),
                        false, "mustCallTools"),

                // 4 pass: mustCallTools present
                args("pass_must_call_present",
                        steps(
                                llm("phase1", "{\"tool\":\"query_mastery\"}"),
                                tool("query_mastery", "{\"nodeCode\":\"KT01\"}",
                                        "{\"ok\":true,\"raw\":0.3}"),
                                llm("phase2", "你的掌握度偏低")),
                        "你的掌握度偏低，建议复习 KT01",
                        hard(List.of("query_mastery"), List.of("schedule_review"), 4, true, true, false),
                        Set.of("KT01"),
                        true, null),

                // 5 fail: mustNotCallTools hit
                args("fail_must_not_call",
                        steps(
                                tool("schedule_review", "{\"nodeCode\":\"KT01\"}", "{\"ok\":true}"),
                                llm("phase2", "已安排")),
                        "已安排复习",
                        hard(List.of(), List.of("schedule_review"), 4, true, true, false),
                        Set.of("KT01"),
                        false, "mustNotCallTools"),

                // 6 fail: maxToolCalls exceeded
                args("fail_max_tool_calls",
                        steps(
                                tool("query_mastery", "{}", "{\"a\":1}"),
                                tool("get_review_queue", "{}", "{\"items\":[]}"),
                                tool("get_node_detail", "{}", "{\"code\":\"KT01\"}"),
                                llm("phase2", "done")),
                        "done",
                        hard(List.of(), List.of(), 2, true, true, false),
                        Set.of("KT01"),
                        false, "maxToolCalls"),

                // 7 pass: maxToolCalls boundary equal
                args("pass_max_tool_calls_eq",
                        steps(
                                tool("query_mastery", "{}", "{\"a\":1}"),
                                tool("get_review_queue", "{}", "{\"items\":[]}"),
                                llm("phase2", "ok")),
                        "ok",
                        hard(List.of(), List.of(), 2, true, true, false),
                        Set.of(),
                        true, null),

                // 8 fail: jsonContract invalid tool output
                args("fail_json_contract_tool_output",
                        steps(
                                tool("query_mastery", "{\"nodeCode\":\"KT01\"}", "not-json"),
                                llm("phase2", "x")),
                        "x",
                        hard(List.of(), List.of(), 4, true, true, false),
                        Set.of("KT01"),
                        false, "jsonContractValid"),

                // 8b fail: llm_round trace output that looks like JSON must be valid JSON
                args("fail_json_contract_llm_round_output",
                        steps(llm("llm_round", "{\"round\":0,\"toolCalls\":[}")),
                        "x",
                        hard(List.of(), List.of(), 4, true, true, false),
                        Set.of(),
                        false, "jsonContractValid"),

                // 8c pass: valid llm_round output
                args("pass_json_contract_llm_round_output",
                        steps(llm("llm_round",
                                "{\"round\":0,\"toolCalls\":[],\"contentChars\":1,\"finishReason\":\"stop\"}")),
                        "x",
                        hard(List.of(), List.of(), 4, true, true, false),
                        Set.of(),
                        true, null),

                // 9 pass: jsonContractValid=false skips
                args("pass_json_contract_skipped",
                        steps(
                                tool("query_mastery", "bad", "also-bad"),
                                llm("phase2", "x")),
                        "x",
                        hard(List.of(), List.of(), 4, false, false, false),
                        Set.of(),
                        true, null),

                // 10 fail: node code outside whitelist
                args("fail_node_outside_whitelist",
                        steps(llm("phase2", "请学 FAKE-99 节点")),
                        "请先掌握 FAKE-99 再继续",
                        hard(List.of(), List.of(), 4, true, true, false),
                        Set.of("KT01", "KT02"),
                        false, "nodeCodesInWhitelist"),

                // 11 pass: node codes all in whitelist
                args("pass_nodes_in_whitelist",
                        steps(
                                tool("query_mastery", "{\"nodeCode\":\"KT01\"}",
                                        "{\"nodeCode\":\"KT02\",\"ok\":true}"),
                                llm("phase2", "KT01 与 KT02 相关")),
                        "建议复习 KT01，再看 KT02",
                        hard(List.of("query_mastery"), List.of(), 4, true, true, false),
                        Set.of("KT01", "KT02"),
                        true, null),

                // 12 fail: degradedExpected true but no DEGRADE
                args("fail_degrade_expected_missing",
                        steps(llm("phase2", "normal")),
                        "normal reply",
                        hard(List.of(), List.of(), 4, true, true, true),
                        Set.of(),
                        false, "degradedExpected"),

                // 13 pass: degradedExpected true with DEGRADE step
                args("pass_degrade_expected",
                        steps(
                                new EvalStepView("DEGRADE", "budget", 0, "{}", "{\"degraded\":true}"),
                                llm("phase2", "我先用简化回答")),
                        "我先用简化回答",
                        hard(List.of(), List.of(), 4, true, true, true),
                        Set.of(),
                        true, null),

                // 14 fail: degraded unexpected
                args("fail_degrade_unexpected",
                        steps(
                                new EvalStepView("DEGRADE", "err", 0, null, null),
                                llm("phase2", "fallback")),
                        "fallback text",
                        hard(List.of(), List.of(), 4, true, true, false),
                        Set.of(),
                        false, "degradedExpected"),

                // 15 pass: GUARD counts toward max but not mustCall
                args("pass_guard_counts_budget_not_must_call",
                        steps(
                                new EvalStepView("GUARD", "start_practice", 1,
                                        "{\"nodeCode\":\"KT01\"}",
                                        "{\"code\":\"BUDGET_EXCEEDED\"}"),
                                llm("phase2", "今日练习次数已用完")),
                        "今日练习次数已用完",
                        hard(List.of(), List.of(), 1, true, true, false),
                        Set.of("KT01"),
                        true, null),

                // 16 fail: GUARD exceeds maxToolCalls
                args("fail_guard_exceeds_max",
                        steps(
                                new EvalStepView("GUARD", "a", 1, "{}", "{}"),
                                new EvalStepView("GUARD", "b", 1, "{}", "{}"),
                                llm("phase2", "stop")),
                        "stop",
                        hard(List.of(), List.of(), 1, true, true, false),
                        Set.of(),
                        false, "maxToolCalls")
        );
    }

    private static Arguments args(String name,
                                  List<EvalStepView> steps,
                                  String response,
                                  HardAssertions hard,
                                  Set<String> whitelist,
                                  boolean expectPass,
                                  String expectedFailureCheck) {
        return Arguments.of(Named.of(name, new Case(
                steps, response, hard, whitelist, expectPass, expectedFailureCheck)));
    }

    private static HardAssertions hard(List<String> must,
                                       List<String> mustNot,
                                       Integer max,
                                       Boolean json,
                                       Boolean white,
                                       Boolean degraded) {
        return new HardAssertions(must, mustNot, max, json, white, degraded);
    }

    private static List<EvalStepView> steps(EvalStepView... s) {
        return List.of(s);
    }

    private static EvalStepView llm(String name, String out) {
        return new EvalStepView("LLM_CALL", name, 0, null, out);
    }

    private static EvalStepView tool(String name, String in, String out) {
        return new EvalStepView("TOOL_CALL", name, 0, in, out);
    }

    record Case(
            List<EvalStepView> steps,
            String response,
            HardAssertions hard,
            Set<String> whitelist,
            boolean expectPass,
            String expectedFailureCheck
    ) {}
}
