package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Companion tools run under cooperative cancellation.
 * Implementations should honor thread interruption and configure downstream I/O timeouts,
 * because {@link ToolGovernor} returns promptly and only cancels work cooperatively.
 */
public interface CompanionTool {

    String name();

    Map<String, Object> schema();

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

    String execute(ToolContext ctx, JsonNode args) throws ToolException;

    default ToolExecutionResult executeForGovernor(String toolCallId,
                                                   ToolContext ctx,
                                                   JsonNode args) throws ToolException {
        return new ToolExecutionResult(toolCallId, name(), true, execute(ctx, args));
    }
}
