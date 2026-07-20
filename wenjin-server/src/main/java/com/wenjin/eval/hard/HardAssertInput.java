package com.wenjin.eval.hard;

import com.wenjin.eval.scenario.EvalScenarioDocument.HardAssertions;

import java.util.List;
import java.util.Set;

/**
 * Inputs to {@link HardAssertChecker}: run steps + final response + hard assertions + whitelist.
 */
public record HardAssertInput(
        List<EvalStepView> steps,
        String finalResponse,
        HardAssertions hard,
        Set<String> nodeCodeWhitelist
) {
    public HardAssertInput {
        steps = steps == null ? List.of() : List.copyOf(steps);
        finalResponse = finalResponse == null ? "" : finalResponse;
        nodeCodeWhitelist = nodeCodeWhitelist == null ? Set.of() : Set.copyOf(nodeCodeWhitelist);
    }
}
