package com.wenjin.eval.hard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.eval.scenario.EvalScenarioDocument.HardAssertions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Table-friendly hard assertion checker for LIVE/REPLAY eval runs.
 * <p>Pure logic, no Spring. Input: run steps + final response + hard spec + node whitelist.
 */
public final class HardAssertChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Common node-code shapes in this project: {@code KT01}, {@code SE-3.2}, etc.
     */
    private static final Pattern NODE_CODE_PATTERN = Pattern.compile(
            "\\b(?:[A-Z]{1,6}-?\\d+(?:\\.\\d+)?|[A-Z]{2}\\d{2,})\\b");

    public HardAssertResult check(HardAssertInput input) {
        Objects.requireNonNull(input, "input");
        HardAssertions hard = input.hard();
        if (hard == null) {
            return HardAssertResult.fail(List.of(
                    new HardAssertFailure("hard", "hard assertions are required")));
        }

        List<HardAssertFailure> failures = new ArrayList<>();
        List<EvalStepView> steps = input.steps();
        String response = input.finalResponse();

        checkNonEmptyResponse(response, failures);
        checkMustCallTools(hard, steps, failures);
        checkMustNotCallTools(hard, steps, failures);
        checkMaxToolCalls(hard, steps, failures);
        checkJsonContract(hard, steps, failures);
        checkNodeCodesWhitelist(hard, steps, response, input.nodeCodeWhitelist(), failures);
        checkDegraded(hard, steps, failures);
        checkCitationsValid(hard, steps, failures);
        checkReplyMustNotContain(hard, response, failures);

        return HardAssertResult.fail(failures);
    }

    private static void checkNonEmptyResponse(String response, List<HardAssertFailure> failures) {
        if (response == null || response.isBlank()) {
            failures.add(new HardAssertFailure("nonEmptyResponse", "final response is empty"));
        }
    }

    private static void checkMustCallTools(HardAssertions hard,
                                           List<EvalStepView> steps,
                                           List<HardAssertFailure> failures) {
        List<String> required = hard.mustCallTools();
        if (required == null || required.isEmpty()) {
            return;
        }
        Set<String> called = toolNames(steps, true);
        List<String> missing = required.stream()
                .filter(t -> t != null && !t.isBlank())
                .filter(t -> !called.contains(t))
                .toList();
        if (!missing.isEmpty()) {
            failures.add(new HardAssertFailure("mustCallTools",
                    "missing required tools: " + missing + "; called=" + called));
        }
    }

    private static void checkMustNotCallTools(HardAssertions hard,
                                              List<EvalStepView> steps,
                                              List<HardAssertFailure> failures) {
        List<String> forbidden = hard.mustNotCallTools();
        if (forbidden == null || forbidden.isEmpty()) {
            return;
        }
        Set<String> called = toolNames(steps, true);
        List<String> hit = forbidden.stream()
                .filter(t -> t != null && !t.isBlank())
                .filter(called::contains)
                .toList();
        if (!hit.isEmpty()) {
            failures.add(new HardAssertFailure("mustNotCallTools",
                    "forbidden tools were called: " + hit));
        }
    }

    private static void checkMaxToolCalls(HardAssertions hard,
                                          List<EvalStepView> steps,
                                          List<HardAssertFailure> failures) {
        if (hard.maxToolCalls() == null) {
            return;
        }
        int max = hard.maxToolCalls();
        long attempts = steps.stream().filter(EvalStepView::countsAsToolAttempt).count();
        if (attempts > max) {
            failures.add(new HardAssertFailure("maxToolCalls",
                    "tool attempts=" + attempts + " exceeds maxToolCalls=" + max));
        }
    }

    private static void checkJsonContract(HardAssertions hard,
                                          List<EvalStepView> steps,
                                          List<HardAssertFailure> failures) {
        if (!Boolean.TRUE.equals(hard.jsonContractValid())) {
            return;
        }
        for (EvalStepView step : steps) {
            if (step.isToolCall() || step.isGuard()) {
                if (!isValidJsonOrEmpty(step.outputJson())) {
                    failures.add(new HardAssertFailure("jsonContractValid",
                            "tool step '" + step.name() + "' output is not valid JSON"));
                }
                if (!isValidJsonOrEmpty(step.inputJson())) {
                    failures.add(new HardAssertFailure("jsonContractValid",
                            "tool step '" + step.name() + "' input is not valid JSON"));
                }
            }
            if (step.isLlmCall() && "llm_round".equals(step.name())) {
                // llm_round trace output is a JSON round summary; only validate if it looks like JSON
                String out = step.outputJson();
                if (out != null && looksLikeJson(out) && !isValidJsonOrEmpty(out)) {
                    failures.add(new HardAssertFailure("jsonContractValid",
                            "llm_round output looks like JSON but is invalid"));
                }
            }
        }
    }

    private static void checkNodeCodesWhitelist(HardAssertions hard,
                                                List<EvalStepView> steps,
                                                String response,
                                                Set<String> whitelist,
                                                List<HardAssertFailure> failures) {
        if (!Boolean.TRUE.equals(hard.nodeCodesInWhitelist())) {
            return;
        }
        if (whitelist == null || whitelist.isEmpty()) {
            // Without a whitelist, only fail if we cannot validate — treat as pass with caution.
            // Callers should supply course node codes; empty whitelist skips extraction enforcement.
            return;
        }
        Set<String> whitelistNorm = whitelist.stream()
                .filter(Objects::nonNull)
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> found = new LinkedHashSet<>();
        found.addAll(extractNodeCodes(response));
        for (EvalStepView step : steps) {
            found.addAll(extractNodeCodes(step.inputJson()));
            found.addAll(extractNodeCodes(step.outputJson()));
        }

        List<String> outsiders = found.stream()
                .filter(code -> !whitelistNorm.contains(code.toUpperCase(Locale.ROOT)))
                .toList();
        if (!outsiders.isEmpty()) {
            failures.add(new HardAssertFailure("nodeCodesInWhitelist",
                    "node codes outside whitelist: " + outsiders));
        }
    }

    private static void checkDegraded(HardAssertions hard,
                                      List<EvalStepView> steps,
                                      List<HardAssertFailure> failures) {
        boolean expected = Boolean.TRUE.equals(hard.degradedExpected());
        boolean hasDegrade = steps.stream().anyMatch(EvalStepView::isDegrade);
        if (expected && !hasDegrade) {
            failures.add(new HardAssertFailure("degradedExpected",
                    "expected DEGRADE step but none found"));
        }
        if (!expected && hard.degradedExpected() != null && hasDegrade) {
            // only fail when explicitly false
            failures.add(new HardAssertFailure("degradedExpected",
                    "DEGRADE step present but degradedExpected=false"));
        }
    }

    /**
     * {@code citationsValid=true}：每个 citation_check 步的 {@code invalid} 必须为空。没有该步 = 回复未引用任何资料，
     * 视为通过（"引用是否被正确使用"由 judge 的 citation_faithfulness 维度与 golden 回放测试把关）。
     */
    private static void checkCitationsValid(HardAssertions hard,
                                            List<EvalStepView> steps,
                                            List<HardAssertFailure> failures) {
        if (!Boolean.TRUE.equals(hard.citationsValid())) {
            return;
        }
        for (EvalStepView step : steps) {
            if (!step.isCitation()) {
                continue;
            }
            try {
                JsonNode invalid = MAPPER.readTree(step.outputJson() == null ? "" : step.outputJson()).path("invalid");
                if (!invalid.isArray()) {
                    failures.add(new HardAssertFailure("citationsValid",
                            "citation_check output has no 'invalid' array: " + step.outputJson()));
                } else if (!invalid.isEmpty()) {
                    failures.add(new HardAssertFailure("citationsValid",
                            "reply cites chunk ids outside the turn's citable set: " + invalid));
                }
            } catch (Exception e) {
                failures.add(new HardAssertFailure("citationsValid",
                        "citation_check output is not valid JSON: " + step.outputJson()));
            }
        }
    }

    private static void checkReplyMustNotContain(HardAssertions hard,
                                                 String response,
                                                 List<HardAssertFailure> failures) {
        List<String> forbidden = hard.replyMustNotContain();
        if (forbidden == null || forbidden.isEmpty() || response == null) {
            return;
        }
        for (String s : forbidden) {
            if (s != null && !s.isBlank() && response.contains(s)) {
                failures.add(new HardAssertFailure("replyMustNotContain",
                        "final response contains forbidden text: " + s));
            }
        }
    }

    private static Set<String> toolNames(List<EvalStepView> steps, boolean onlySuccessfulToolCall) {
        Set<String> names = new LinkedHashSet<>();
        for (EvalStepView step : steps) {
            if (step.isToolCall()) {
                if (step.name() != null && !step.name().isBlank()) {
                    names.add(step.name());
                }
            }
        }
        return names;
    }

    static Set<String> extractNodeCodes(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Matcher m = NODE_CODE_PATTERN.matcher(text);
        Set<String> codes = new LinkedHashSet<>();
        while (m.find()) {
            codes.add(m.group());
        }
        return codes;
    }

    private static boolean looksLikeJson(String s) {
        String t = s.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    private static boolean isValidJsonOrEmpty(String s) {
        if (s == null || s.isBlank()) {
            return true;
        }
        String t = s.trim();
        if (!looksLikeJson(t)) {
            // free text tool envelopes still often JSON; non-JSON treated as invalid when contract required
            // Allow plain non-JSON only when not brace-shaped — treat non-JSON as fail for tool I/O
            return false;
        }
        try {
            JsonNode node = MAPPER.readTree(t);
            return node != null;
        } catch (Exception e) {
            return false;
        }
    }
}
