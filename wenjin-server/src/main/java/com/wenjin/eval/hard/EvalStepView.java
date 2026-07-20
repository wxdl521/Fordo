package com.wenjin.eval.hard;

/**
 * Minimal agent step view for hard assertions (decoupled from DB entity).
 */
public record EvalStepView(
        String stepType,
        String name,
        int status,
        String inputJson,
        String outputJson
) {
    public EvalStepView {
        stepType = stepType == null ? "" : stepType;
        name = name == null ? "" : name;
    }

    public boolean isToolCall() {
        return "TOOL_CALL".equals(stepType);
    }

    public boolean isGuard() {
        return "GUARD".equals(stepType);
    }

    public boolean isDegrade() {
        return "DEGRADE".equals(stepType);
    }

    public boolean isLlmCall() {
        return "LLM_CALL".equals(stepType);
    }

    /** TOOL_CALL or GUARD counts toward maxToolCalls budget. */
    public boolean countsAsToolAttempt() {
        return isToolCall() || isGuard();
    }
}
