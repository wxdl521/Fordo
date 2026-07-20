package com.wenjin.eval.judge;

/**
 * Minimal AI surface for the LLM judge (inject fake in unit tests).
 */
@FunctionalInterface
public interface JudgeAiClient {

    /**
     * Complete a single judge prompt; return raw model text (ideally JSON).
     */
    String complete(String prompt);
}
