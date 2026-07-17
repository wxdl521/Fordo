package com.wenjin.support;

/**
 * agent_step.step_type 取值。
 */
public final class AgentStepType {

    public static final String LLM_CALL = "LLM_CALL";
    public static final String TOOL_CALL = "TOOL_CALL";
    public static final String GUARD = "GUARD";
    public static final String DEGRADE = "DEGRADE";
    public static final String REFLECT = "REFLECT";
    public static final String MEMORY = "MEMORY";
    public static final String RETRIEVE = "RETRIEVE";

    private AgentStepType() {}
}
