package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Companion tools run under cooperative cancellation.
 * Implementations should honor thread interruption and configure downstream I/O timeouts,
 * because {@link ToolGovernor} returns promptly and only cancels work cooperatively.
 */
public interface CompanionTool {

    String name();

    Map<String, Object> schema();

    /**
     * Declarative param contract for governor pre-validation.
     * Empty list → only JSON-schema whitelist validation runs.
     */
    default List<ToolParamSpec> params() {
        return List.of();
    }

    default String label() {
        Map<String, Object> toolSchema = schema();
        if (toolSchema == null) {
            return name();
        }
        Object function = toolSchema.get("function");
        if (function instanceof Map<?, ?> functionMap) {
            Object description = functionMap.get("description");
            if (description instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return name();
    }

    /**
     * Side-effecting tools run inline so the caller never receives a timeout while
     * the work may still commit in the background after cooperative cancellation.
     */
    default boolean sideEffecting() {
        return false;
    }

    /**
     * Runtime availability beyond feature flags (e.g. an external sandbox is reachable).
     * {@code CompanionServiceImpl} drops unavailable tools from the schema offered to the model.
     */
    default boolean available() {
        return true;
    }

    /**
     * Privacy hook: returns the tool-call arguments in a form that is safe to persist in the agent trace
     * and print in the governor audit log. Default is the identity; tools whose arguments carry
     * student-authored content (e.g. source code) override it.
     */
    default String redactArgumentsForTrace(String argumentsJson) {
        return argumentsJson;
    }

    /**
     * Privacy hook for the governor audit log: returns the tool <b>result</b> in a form safe to print.
     * Default is the identity; tools whose results carry student-authored output (e.g. program stdout,
     * test-case contents) override it. The model and the agent trace still receive the full result.
     */
    default String redactResultForLog(String resultContent) {
        return resultContent;
    }

    String execute(ToolContext ctx, JsonNode args) throws ToolException;

    default ToolExecutionResult executeForGovernor(String toolCallId,
                                                   ToolContext ctx,
                                                   JsonNode args) throws ToolException {
        return new ToolExecutionResult(toolCallId, name(), true, execute(ctx, args));
    }
}
