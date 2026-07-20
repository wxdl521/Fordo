package com.wenjin.support;

import com.wenjin.ai.CompanionAiClient;

import java.util.Objects;

/**
 * Parsed phase-1 decision JSON: intent + tool/final outcome.
 *
 * <p>Contract (M9):
 * <pre>
 * {"type":"tool_calls"|"final_text", "intent":"EXPLAIN|QUERY|PRACTICE|CHAT", ...}
 * </pre>
 * Missing {@code intent} → {@link CompanionIntent#CHAT}.
 */
public record Phase1Decision(CompanionIntent intent, CompanionAiClient.TurnOutcome outcome) {

    public Phase1Decision {
        intent = intent == null ? CompanionIntent.CHAT : intent;
        Objects.requireNonNull(outcome, "outcome");
    }

    public static Phase1Decision of(CompanionIntent intent, CompanionAiClient.TurnOutcome outcome) {
        return new Phase1Decision(intent, outcome);
    }

    public static Phase1Decision chat(CompanionAiClient.TurnOutcome outcome) {
        return new Phase1Decision(CompanionIntent.CHAT, outcome);
    }
}
