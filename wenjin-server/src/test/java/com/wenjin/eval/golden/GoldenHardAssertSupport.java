package com.wenjin.eval.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.eval.hard.EvalStepView;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.EvalScenarioDocument.MasteryOverride;
import com.wenjin.eval.scenario.EvalScenarioDocument.Persona;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Expand a companion golden ({@link GoldenFile} LLM_CALL sequence) into
 * {@link EvalStepView} steps suitable for {@code HardAssertChecker}.
 * <p>phase1 tool_calls → synthetic TOOL_CALL steps with valid JSON I/O;
 * last phase2 text becomes the final response.
 */
public final class GoldenHardAssertSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoldenHardAssertSupport() {}

    public record ExpandedRun(List<EvalStepView> steps, String finalResponse) {}

    public static ExpandedRun expand(GoldenFile golden) {
        List<EvalStepView> steps = new ArrayList<>();
        String finalResponse = "";
        if (golden == null || golden.llmCalls() == null) {
            return new ExpandedRun(List.of(), "");
        }
        for (GoldenLlmCall call : golden.llmCalls()) {
            String name = call.name() == null ? "" : call.name();
            String output = call.output() == null ? "" : call.output();
            if ("phase1".equals(name)) {
                steps.add(new EvalStepView("LLM_CALL", "phase1", 0, null, output));
                steps.addAll(syntheticToolStepsFromPhase1(output));
            } else if ("phase2".equals(name)) {
                steps.add(new EvalStepView("LLM_CALL", "phase2", 0, null, output));
                if (!output.isBlank()) {
                    finalResponse = output;
                }
                // M9 synthetic degrade marker in recorded phase2 text
                if (output != null && output.contains("无法调用大模型")) {
                    steps.add(0, new EvalStepView("DEGRADE", "LLM_FAILURES", 0,
                            "{}", "{\"degraded\":true,\"reason\":\"LLM_FAILURES\"}"));
                }
            } else if ("degrade".equalsIgnoreCase(name)) {
                steps.add(new EvalStepView("DEGRADE", "LLM_FAILURES", 0, null, output));
            } else {
                steps.add(new EvalStepView("LLM_CALL", name, 0, null, output));
            }
        }
        return new ExpandedRun(List.copyOf(steps), finalResponse);
    }

    /**
     * Whitelist = course baseline ∪ persona mastery/review codes.
     */
    public static Set<String> whitelistFor(EvalScenarioDocument scenario) {
        Set<String> codes = new LinkedHashSet<>(EnabledScenarioStepsFactory.COURSE_NODE_WHITELIST);
        if (scenario == null || scenario.persona() == null) {
            return Set.copyOf(codes);
        }
        Persona persona = scenario.persona();
        if (persona.masteryOverrides() != null) {
            for (MasteryOverride o : persona.masteryOverrides()) {
                if (o != null && o.nodeCode() != null && !o.nodeCode().isBlank()) {
                    codes.add(o.nodeCode().trim());
                }
            }
        }
        if (persona.reviewQueue() != null) {
            for (String c : persona.reviewQueue()) {
                if (c != null && !c.isBlank()) {
                    codes.add(c.trim());
                }
            }
        }
        return Set.copyOf(codes);
    }

    static List<EvalStepView> syntheticToolStepsFromPhase1(String phase1Output) {
        List<EvalStepView> tools = new ArrayList<>();
        if (phase1Output == null || phase1Output.isBlank()) {
            return tools;
        }
        String trimmed = phase1Output.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return tools;
        }
        try {
            JsonNode root = MAPPER.readTree(trimmed);
            String type = root.path("type").asText("");
            if (!"tool_calls".equals(type) && !root.has("calls")) {
                return tools;
            }
            JsonNode calls = root.path("calls");
            if (!calls.isArray()) {
                return tools;
            }
            int i = 0;
            for (JsonNode c : calls) {
                String toolName = c.path("name").asText("");
                if (toolName.isBlank()) {
                    continue;
                }
                String args = c.path("arguments").asText("{}");
                if (args == null || args.isBlank()) {
                    args = "{}";
                }
                // Ensure JSON contract for tool input
                if (!looksLikeJson(args)) {
                    args = "{}";
                }
                String out = defaultToolOutput(toolName, args);
                tools.add(new EvalStepView("TOOL_CALL", toolName, 0, args, out));
                i++;
            }
        } catch (Exception ignored) {
            // not expandable — leave only LLM_CALL
        }
        return tools;
    }

    private static String defaultToolOutput(String toolName, String args) {
        return switch (toolName) {
            case "query_mastery" -> "{\"ok\":true,\"raw\":0.5,\"effective\":0.45}";
            case "get_review_queue" -> "{\"ok\":true,\"items\":[]}";
            case "get_node_detail" -> "{\"ok\":true,\"summary\":\"node detail\"}";
            case "trace_prerequisites" -> "{\"ok\":true,\"prerequisites\":[]}";
            case "start_practice" -> "{\"ok\":true,\"sessionId\":1}";
            case "schedule_review" -> "{\"ok\":true}";
            default -> "{\"ok\":true}";
        };
    }

    private static boolean looksLikeJson(String s) {
        String t = s.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }
}
