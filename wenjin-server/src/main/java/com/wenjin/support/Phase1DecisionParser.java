package com.wenjin.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.CompanionAiClient.FinalText;
import com.wenjin.ai.CompanionAiClient.ToolCall;
import com.wenjin.ai.CompanionAiClient.ToolCalls;
import com.wenjin.ai.CompanionAiClient.TurnOutcome;
import com.wenjin.eval.replay.ReplayCompanionAiClient;

/**
 * Unit-testable contract parser for companion phase-1 decision JSON (M9).
 *
 * <p>Reuses {@link ReplayCompanionAiClient#parsePhase1(String)} for tool/final
 * outcome shapes; additionally extracts {@code intent} with CHAT default.
 */
public final class Phase1DecisionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Phase1DecisionParser() {}

    /**
     * Parse phase-1 model output into intent + turn outcome.
     * Non-JSON / blank → CHAT + FinalText of the raw string (possibly empty).
     */
    public static Phase1Decision parse(String output) {
        CompanionIntent intent = extractIntent(output);
        TurnOutcome outcome = ReplayCompanionAiClient.parsePhase1(output);
        return Phase1Decision.of(intent, outcome);
    }

    /**
     * Extract intent only; missing/invalid → {@link CompanionIntent#CHAT}.
     */
    public static CompanionIntent extractIntent(String output) {
        if (output == null || output.isBlank()) {
            return CompanionIntent.CHAT;
        }
        String trimmed = output.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return CompanionIntent.CHAT;
        }
        try {
            JsonNode root = MAPPER.readTree(trimmed);
            if (root == null || root.isMissingNode() || root.isNull()) {
                return CompanionIntent.CHAT;
            }
            // Compact golden: top-level "intent"
            if (root.has("intent")) {
                return CompanionIntent.fromRaw(root.path("intent").asText(null));
            }
            // OpenAI chat.completion shape: intent may sit on message content JSON — not required
            return CompanionIntent.CHAT;
        } catch (Exception e) {
            return CompanionIntent.CHAT;
        }
    }

    /**
     * Convenience: intent from an already-parsed {@link TurnOutcome} path is not available;
     * use {@link #parse(String)} when raw phase1 JSON is present.
     */
    public static boolean isExplain(Phase1Decision decision) {
        return decision != null && decision.intent() == CompanionIntent.EXPLAIN;
    }

    /** Whether outcome is tool_calls with at least one call. */
    public static boolean hasToolCalls(Phase1Decision decision) {
        if (decision == null || !(decision.outcome() instanceof ToolCalls tools)) {
            return false;
        }
        return tools.calls() != null && !tools.calls().isEmpty();
    }

    public static boolean isFinalText(Phase1Decision decision) {
        return decision != null && decision.outcome() instanceof FinalText;
    }

    /** For tests / diagnostics: rebuild compact names-only phase1 from tools. */
    public static String namesOnlyTrace(ToolCalls tools) {
        if (tools == null || tools.calls() == null) {
            return "{\"type\":\"tool_calls\",\"names\":[]}";
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < tools.calls().size(); i++) {
            if (i > 0) {
                names.append(',');
            }
            ToolCall c = tools.calls().get(i);
            names.append('"').append(c == null || c.name() == null ? "" : c.name()).append('"');
        }
        return "{\"type\":\"tool_calls\",\"names\":[" + names + "]}";
    }
}
