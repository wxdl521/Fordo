package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.CompanionToolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tool execution is governed cooperatively: timeouts return promptly, and interruption is
 * signaled to the running tool rather than forcing an external kill.
 */
@Component
public class ToolGovernor {

    private static final Logger log = LoggerFactory.getLogger(ToolGovernor.class);

    private final Map<String, CompanionTool> registry;
    private final ObjectMapper objectMapper;
    private final CompanionToolProperties properties;

    public ToolGovernor(List<CompanionTool> tools,
                        ObjectMapper objectMapper,
                        CompanionToolProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        Map<String, CompanionTool> map = new LinkedHashMap<>();
        if (tools != null) {
            for (CompanionTool tool : tools) {
                if (tool != null && tool.name() != null && !tool.name().isBlank()) {
                    map.put(tool.name(), tool);
                }
            }
        }
        this.registry = Map.copyOf(map);
    }

    public ToolExecutionResult execute(ToolContext ctx,
                                       CompanionAiClient.ToolCall call,
                                       ToolExecutionState state) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(state, "state");
        long started = System.nanoTime();
        String toolCallId = call.id() == null ? "" : call.id();
        String toolName = call.name() == null ? "" : call.name();
        String argsJson = call.argumentsJson() == null ? "{}" : call.argumentsJson();
        ToolExecutionResult result;

        CompanionTool tool = registry.get(toolName);
        if (tool == null) {
            result = failed(toolCallId, toolName, "unknown_tool", "Tool is not registered: " + toolName);
            audit(ctx, toolName, argsJson, started, result);
            return result;
        }

        JsonNode args;
        try {
            args = objectMapper.readTree(argsJson);
            if (args == null || args.isNull()) {
                args = objectMapper.createObjectNode();
            }
        } catch (JsonProcessingException e) {
            result = failed(toolCallId, toolName, "invalid_arguments", "Tool arguments must be valid JSON");
            audit(ctx, toolName, argsJson, started, result);
            return result;
        }
        String validationError = validateArguments(tool, args);
        if (validationError != null) {
            result = failed(toolCallId, toolName, "invalid_arguments", validationError);
            audit(ctx, toolName, argsJson, started, result);
            return result;
        }

        if (state == null || !state.tryConsume()) {
            result = failed(toolCallId, toolName, "tool_budget_exceeded", "Tool call budget exceeded");
            audit(ctx, toolName, argsJson, started, result);
            return result;
        }

        result = executeWithTimeout(ctx, call, tool, args);
        audit(ctx, toolName, argsJson, started, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private String validateArguments(CompanionTool tool, JsonNode args) {
        if (!args.isObject()) {
            return "Tool arguments must be a JSON object";
        }
        Map<String, Object> toolSchema = tool.schema();
        Object function = toolSchema == null ? null : toolSchema.get("function");
        if (!(function instanceof Map<?, ?> functionMap)) {
            return null;
        }
        Object parameters = functionMap.get("parameters");
        if (!(parameters instanceof Map<?, ?> parameterMap)) {
            return null;
        }
        Object properties = parameterMap.get("properties");
        Map<String, Object> propertyMap = properties instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        Set<String> allowed = propertyMap.keySet();

        var fieldNames = args.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!allowed.contains(fieldName)) {
                return "Unknown tool argument: " + fieldName;
            }
        }

        Object required = parameterMap.get("required");
        if (required instanceof List<?> requiredFields) {
            for (Object field : requiredFields) {
                if (field instanceof String fieldName
                        && (!args.has(fieldName) || args.get(fieldName).isNull())) {
                    return "Missing required tool argument: " + fieldName;
                }
            }
        }

        for (Map.Entry<String, Object> entry : propertyMap.entrySet()) {
            JsonNode value = args.get(entry.getKey());
            if (value == null || value.isNull()) {
                continue;
            }
            String error = validateArgumentType(entry.getKey(), value, entry.getValue());
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    private String validateArgumentType(String fieldName, JsonNode value, Object propertySchema) {
        if (!(propertySchema instanceof Map<?, ?> schema)) {
            return null;
        }
        Object type = schema.get("type");
        if (!(type instanceof String expectedType)) {
            return null;
        }
        boolean valid = switch (expectedType) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber() && value.canConvertToInt();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> true;
        };
        return valid ? null : "Tool argument has invalid type: " + fieldName;
    }

    private ToolExecutionResult executeWithTimeout(ToolContext ctx,
                                                   CompanionAiClient.ToolCall call,
                                                   CompanionTool tool,
                                                   JsonNode args) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<ToolExecutionResult> future = executor.submit(() -> tool.executeForGovernor(call.id(), ctx, args));
        try {
            ToolExecutionResult result = future.get(timeoutMs(), TimeUnit.MILLISECONDS);
            if (result == null) {
                return failed(call.id(), call.name(), "tool_error", "Tool execution failed");
            }
            if (!result.ok()) {
                return new ToolExecutionResult(
                        result.toolCallId(),
                        result.toolName(),
                        false,
                        result.content());
            }
            return new ToolExecutionResult(
                    result.toolCallId(),
                    result.toolName(),
                    true,
                    truncateSuccessfulContent(result.content()),
                    result.practiceCard());
        } catch (TimeoutException e) {
            future.cancel(true);
            return failed(call.id(), call.name(), "tool_timeout", "Tool timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(call.id(), call.name(), "tool_interrupted", "Tool execution was interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause == null ? "Tool execution failed" : cause.getMessage();
            return failed(call.id(), call.name(), "tool_error", message);
        } finally {
            executor.shutdownNow();
        }
    }

    private long timeoutMs() {
        long timeout = properties == null ? 5000 : properties.getTimeoutMs();
        return Math.max(1, timeout);
    }

    private ToolExecutionResult failed(String toolCallId, String toolName, String error, String message) {
        return new ToolExecutionResult(toolCallId, toolName, false, errorContent(error, message));
    }

    private String errorContent(String error, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message == null ? "" : message);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"tool_error\",\"message\":\"Failed to encode tool error\"}";
        }
    }

    private String truncateSuccessfulContent(String content) {
        String safe = content == null ? "" : content;
        int max = properties == null ? 2000 : properties.getMaxResultChars();
        max = Math.max(0, max);
        if (safe.length() <= max) {
            return safe;
        }
        return truncateJsonContent(safe, max);
    }

    private String truncateJsonContent(String content, int max) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(content);
        } catch (JsonProcessingException e) {
            return fitFallbackContent(max);
        }

        JsonNode truncated = addTruncatedFlag(parsed.deepCopy());
        while (serialize(truncated).length() > max) {
            if (trimLastArrayElement(truncated)) {
                continue;
            }
            if (trimLongestStringValue(truncated, serialize(truncated).length() - max)) {
                continue;
            }
            return fitFallbackContent(max);
        }
        return serialize(truncated);
    }

    private JsonNode addTruncatedFlag(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            objectNode.put("truncated", true);
            return objectNode;
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.put("truncated", true);
        wrapper.set("result", node);
        return wrapper;
    }

    private boolean trimLastArrayElement(JsonNode node) {
        if (node instanceof ArrayNode arrayNode) {
            if (!arrayNode.isEmpty()) {
                arrayNode.remove(arrayNode.size() - 1);
                return true;
            }
            return false;
        }
        if (node instanceof ObjectNode objectNode) {
            for (JsonNode child : objectNode) {
                if (trimLastArrayElement(child)) {
                    return true;
                }
            }
            return false;
        }
        for (JsonNode child : node) {
            if (trimLastArrayElement(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean trimLongestStringValue(JsonNode node, int overflow) {
        TrimCandidate candidate = findLongestString(node);
        if (candidate == null || candidate.value().isEmpty()) {
            return false;
        }
        int delta = Math.max(1, overflow);
        int newLength = Math.max(0, candidate.value().length() - delta);
        candidate.replaceWith(candidate.value().substring(0, newLength));
        return true;
    }

    private TrimCandidate findLongestString(JsonNode node) {
        TrimCandidate best = null;
        if (node instanceof ObjectNode objectNode) {
            var fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode child = entry.getValue();
                if (child.isTextual()) {
                    TrimCandidate candidate = TrimCandidate.forField(objectNode, entry.getKey(), child.asText());
                    if (best == null || candidate.value().length() > best.value().length()) {
                        best = candidate;
                    }
                    continue;
                }
                TrimCandidate nested = findLongestString(child);
                if (nested != null && (best == null || nested.value().length() > best.value().length())) {
                    best = nested;
                }
            }
            return best;
        }
        if (node instanceof ArrayNode arrayNode) {
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode child = arrayNode.get(i);
                if (child.isTextual()) {
                    TrimCandidate candidate = TrimCandidate.forIndex(arrayNode, i, child.asText());
                    if (best == null || candidate.value().length() > best.value().length()) {
                        best = candidate;
                    }
                    continue;
                }
                TrimCandidate nested = findLongestString(child);
                if (nested != null && (best == null || nested.value().length() > best.value().length())) {
                    best = nested;
                }
            }
        }
        return best;
    }

    private String fitFallbackContent(int max) {
        for (String candidate : List.of("{\"truncated\":true}", "{}", "\"\"", "0", "")) {
            if (candidate.length() <= max) {
                return candidate;
            }
        }
        return "";
    }

    private String serialize(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return "{\"truncated\":true}";
        }
    }

    private void audit(ToolContext ctx,
                       String toolName,
                       String argsJson,
                       long started,
                       ToolExecutionResult result) {
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        Long studentId = ctx == null ? null : ctx.studentId();
        Long courseId = ctx == null ? null : ctx.courseId();
        log.info("companion_tool uid={} courseId={} tool={} args={} elapsedMs={} ok={} result={}",
                studentId,
                courseId,
                toolName,
                summarize(argsJson, 160),
                elapsedMs,
                result.ok(),
                summarize(result.content(), 160));
    }

    private String summarize(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private sealed interface TrimCandidate permits FieldTrimCandidate, IndexTrimCandidate {
        String value();

        void replaceWith(String value);

        static TrimCandidate forField(ObjectNode parent, String fieldName, String value) {
            return new FieldTrimCandidate(parent, fieldName, value);
        }

        static TrimCandidate forIndex(ArrayNode parent, int index, String value) {
            return new IndexTrimCandidate(parent, index, value);
        }
    }

    private record FieldTrimCandidate(ObjectNode parent, String fieldName, String value) implements TrimCandidate {
        @Override
        public void replaceWith(String value) {
            parent.put(fieldName, value);
        }
    }

    private record IndexTrimCandidate(ArrayNode parent, int index, String value) implements TrimCandidate {
        @Override
        public void replaceWith(String value) {
            parent.set(index, TextNode.valueOf(value));
        }
    }
}
