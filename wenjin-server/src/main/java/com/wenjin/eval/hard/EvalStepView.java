package com.wenjin.eval.hard;

/**
 * Minimal agent step view for hard assertions (decoupled from DB entity).
 * <p>The metric fields ({@code seq, tokensIn, tokensOut, durationMs, errorCode}) are nullable and
 * only filled by LIVE collection; the 5-arg constructor keeps older callers working.
 */
public record EvalStepView(
        String stepType,
        String name,
        int status,
        String inputJson,
        String outputJson,
        Integer seq,
        Integer tokensIn,
        Integer tokensOut,
        Long durationMs,
        String errorCode
) {
    public EvalStepView {
        stepType = stepType == null ? "" : stepType;
        name = name == null ? "" : name;
    }

    public EvalStepView(String stepType, String name, int status, String inputJson, String outputJson) {
        this(stepType, name, status, inputJson, outputJson, null, null, null, null, null);
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
