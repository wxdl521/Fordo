package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.service.trace.ToolCacheEntry;
import com.wenjin.service.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 *
 * <p>Order: resolve tool → parse/validate args (no budget) → cache hit (no budget)
 * → tryConsume → execute. Unknown tools still consume budget (whitelist intercept).
 */
@Component
public class ToolGovernor {

    private static final Logger log = LoggerFactory.getLogger(ToolGovernor.class);

    /**
     * Read-only tools eligible for per-run result de-duplication.
     * Includes future Task-12 names so the set is stable once tools land.
     */
    public static final Set<String> READ_CACHEABLE_TOOLS = Set.of(
            "query_mastery",
            "get_node_detail",
            "get_review_queue",
            "get_learning_frontier",
            "trace_prerequisites");

    private static final String TRUNCATION_MARKER = "[已截断]";

    private final Map<String, CompanionTool> registry;
    private final ObjectMapper objectMapper;
    private final CompanionToolProperties properties;
    private final LimitsProperties limits;

    public ToolGovernor(List<CompanionTool> tools,
                        ObjectMapper objectMapper,
                        CompanionToolProperties properties) {
        this(tools, objectMapper, properties, null);
    }

    public ToolGovernor(List<CompanionTool> tools,
                        ObjectMapper objectMapper,
                        CompanionToolProperties properties,
                        LimitsProperties limits) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.limits = limits;
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

    /** Backward-compatible entry: no per-run cache. */
    public ToolExecutionResult execute(ToolContext ctx,
                                       CompanionAiClient.ToolCall call,
                                       ToolExecutionState state) {
        return execute(ctx, call, state, null);
    }

    public ToolExecutionResult execute(ToolContext ctx,
                                       CompanionAiClient.ToolCall call,
                                       ToolExecutionState state,
                                       TraceContext traceCtx) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(state, "state");
        long started = System.nanoTime();
        String toolCallId = call.id() == null ? "" : call.id();
        String toolName = call.name() == null ? "" : call.name();
        String argsJson = call.argumentsJson() == null ? "{}" : call.argumentsJson();
        ToolExecutionResult result;

        CompanionTool tool = registry.get(toolName);
        if (tool == null) {
            // Unknown tool: still consume budget (whitelist intercept), then GUARD.
            if (!state.tryConsume(false)) {
                result = failed(toolCallId, toolName,
                        ToolErrorCodes.TOOL_BUDGET_EXCEEDED,
                        "本轮工具调用次数已达上限");
            } else {
                result = failed(toolCallId, toolName,
                        ToolErrorCodes.TOOL_NOT_ALLOWED,
                        "工具未授权或不存在: " + toolName);
            }
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
            result = failed(toolCallId, toolName,
                    ToolErrorCodes.TOOL_BAD_ARGS,
                    "Tool arguments must be valid JSON");
            audit(ctx, toolName, argsJson, started, result);
            return result;
        }

        // ParamSpec first (no budget on failure), then schema whitelist as兜底.
        String paramError = ToolParamValidator.validate(tool.params(), args);
        if (paramError != null) {
            result = failed(toolCallId, toolName, ToolErrorCodes.TOOL_BAD_ARGS, paramError);
            audit(ctx, toolName, argsJson, started, result);
            return result;
        }
        String schemaError = validateArguments(tool, args);
        if (schemaError != null) {
            result = failed(toolCallId, toolName, ToolErrorCodes.TOOL_BAD_ARGS, schemaError);
            audit(ctx, toolName, argsJson, started, result);
            return result;
        }

        // Read-only cache hit: return immediately without consuming budget.
        String cacheKey = null;
        if (isCacheable(toolName) && traceCtx != null) {
            cacheKey = cacheKey(toolName, args);
            ToolCacheEntry hit = traceCtx.toolCache().get(cacheKey);
            if (hit != null && hit.payload() != null) {
                result = new ToolExecutionResult(
                        toolCallId, toolName, true, hit.payload(), null, false, true);
                audit(ctx, toolName, argsJson, started, result);
                return result;
            }
        }

        boolean sideEffecting = tool.sideEffecting();
        if (!state.tryConsume(sideEffecting)) {
            result = failed(toolCallId, toolName,
                    ToolErrorCodes.TOOL_BUDGET_EXCEEDED,
                    "本轮工具调用次数已达上限");
            audit(ctx, toolName, argsJson, started, result);
            return result;
        }

        result = sideEffecting
                ? executeInline(ctx, call, tool, args)
                : executeWithTimeout(ctx, call, tool, args);

        if (cacheKey != null && result != null && result.ok() && !result.cached()
                && result.content() != null && traceCtx != null) {
            traceCtx.toolCache().putIfAbsent(cacheKey, new ToolCacheEntry(result.content()));
        }

        audit(ctx, toolName, argsJson, started, result);
        return result;
    }

    private static boolean isCacheable(String toolName) {
        return toolName != null && READ_CACHEABLE_TOOLS.contains(toolName);
    }

    String cacheKey(String toolName, JsonNode args) {
        return toolName + ":" + sha256(canonicalJson(args));
    }

    private String canonicalJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(sortKeys(node == null
                    ? objectMapper.createObjectNode()
                    : node));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private JsonNode sortKeys(JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.nullNode();
        }
        if (node.isObject()) {
            ObjectNode out = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                out.set(name, sortKeys(node.get(name)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                out.add(sortKeys(child));
            }
            return out;
        }
        return node;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the JRE; fall back to identity if somehow missing.
            return Integer.toHexString(text.hashCode());
        }
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
        String anyOfError = validateAnyOfRequired(parameterMap, args);
        if (anyOfError != null) {
            return anyOfError;
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

    private String validateAnyOfRequired(Map<?, ?> parameterMap, JsonNode args) {
        Object anyOf = parameterMap.get("anyOf");
        if (!(anyOf instanceof List<?> candidates) || candidates.isEmpty()) {
            return null;
        }
        Set<String> alternatives = new LinkedHashSet<>();
        boolean hasRequiredCandidate = false;
        for (Object candidate : candidates) {
            if (!(candidate instanceof Map<?, ?> candidateMap)) {
                continue;
            }
            Object required = candidateMap.get("required");
            if (!(required instanceof List<?> requiredFields)) {
                continue;
            }
            boolean hasStringField = false;
            boolean allPresent = true;
            for (Object field : requiredFields) {
                if (field instanceof String fieldName) {
                    alternatives.add(fieldName);
                    hasStringField = true;
                    if (!args.has(fieldName) || args.get(fieldName).isNull()) {
                        allPresent = false;
                    }
                }
            }
            if (!hasStringField) {
                continue;
            }
            hasRequiredCandidate = true;
            if (allPresent) {
                return null;
            }
        }
        if (!hasRequiredCandidate) {
            return null;
        }
        return "Missing required tool argument: one of " + String.join(",", alternatives);
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
            return normalizeResult(call, future.get(timeoutMs(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            return failed(call.id(), call.name(),
                    ToolErrorCodes.TOOL_TIMEOUT,
                    "工具调用超时，可换用其他方式回应");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("companion tool interrupted: {}", call.name());
            return failed(call.id(), call.name(),
                    ToolErrorCodes.TOOL_INTERNAL,
                    "工具执行被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.warn("companion tool failed: {} — {}", call.name(),
                    cause == null ? "unknown" : cause.toString());
            // Tool body started; internal failure stays TOOL_CALL (not GUARD)
            return failedUnguarded(call.id(), call.name(),
                    ToolErrorCodes.TOOL_INTERNAL,
                    "工具执行失败");
        } finally {
            executor.shutdownNow();
        }
    }

    private ToolExecutionResult executeInline(ToolContext ctx,
                                              CompanionAiClient.ToolCall call,
                                              CompanionTool tool,
                                              JsonNode args) {
        try {
            return normalizeResult(call, tool.executeForGovernor(call.id(), ctx, args));
        } catch (Exception e) {
            log.warn("companion tool failed inline: {} — {}", call.name(), e.toString());
            return failedUnguarded(call.id(), call.name(),
                    ToolErrorCodes.TOOL_INTERNAL,
                    "工具执行失败");
        }
    }

    private ToolExecutionResult normalizeResult(CompanionAiClient.ToolCall call, ToolExecutionResult result) {
        if (result == null) {
            return failedUnguarded(call.id(), call.name(),
                    ToolErrorCodes.TOOL_INTERNAL,
                    "工具执行失败");
        }
        if (!result.ok()) {
            // Post-execution tool failure: not a governor intercept.
            // If content is already an envelope keep it; otherwise wrap as internal.
            String content = result.content();
            if (content == null || content.isBlank() || !looksLikeErrorEnvelope(content)) {
                content = errorContent(ToolErrorCodes.TOOL_INTERNAL, "工具执行失败");
            }
            return new ToolExecutionResult(
                    result.toolCallId(),
                    result.toolName(),
                    false,
                    content,
                    null,
                    false,
                    false);
        }
        return new ToolExecutionResult(
                result.toolCallId(),
                result.toolName(),
                true,
                truncateSuccessfulContent(result.content()),
                result.practiceCard(),
                false,
                false);
    }

    private boolean looksLikeErrorEnvelope(String content) {
        try {
            JsonNode node = objectMapper.readTree(content);
            return node != null && node.isObject()
                    && node.has("ok") && node.has("code");
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private long timeoutMs() {
        long timeout = properties == null ? 5000 : properties.getTimeoutMs();
        return Math.max(1, timeout);
    }

    private int maxResultChars() {
        if (limits != null && limits.getTools() != null) {
            return Math.max(0, limits.getTools().getMaxResultChars());
        }
        return Math.max(0, properties == null ? 2000 : properties.getMaxResultChars());
    }

    private ToolExecutionResult failed(String toolCallId,
                                       String toolName,
                                       String code,
                                       String message) {
        return new ToolExecutionResult(
                toolCallId, toolName, false, errorContent(code, message), null, true, false);
    }

    private ToolExecutionResult failedUnguarded(String toolCallId,
                                                String toolName,
                                                String code,
                                                String message) {
        return new ToolExecutionResult(
                toolCallId, toolName, false, errorContent(code, message), null, false, false);
    }

    private String errorContent(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", code == null ? ToolErrorCodes.TOOL_INTERNAL : code);
        body.put("message", message == null ? "" : message);
        body.put("retryable", ToolErrorCodes.retryable(code));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return "{\"ok\":false,\"code\":\"TOOL_INTERNAL\",\"message\":\"Failed to encode tool error\",\"retryable\":true}";
        }
    }

    private String truncateSuccessfulContent(String content) {
        String safe = content == null ? "" : content;
        int max = maxResultChars();
        if (safe.length() <= max) {
            return safe;
        }
        if (max <= 0) {
            return "";
        }
        if (max <= TRUNCATION_MARKER.length()) {
            return TRUNCATION_MARKER.substring(0, max);
        }
        int keep = max - TRUNCATION_MARKER.length();
        return safe.substring(0, keep) + TRUNCATION_MARKER;
    }

    private void audit(ToolContext ctx,
                       String toolName,
                       String argsJson,
                       long started,
                       ToolExecutionResult result) {
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        Long studentId = ctx == null ? null : ctx.studentId();
        Long courseId = ctx == null ? null : ctx.courseId();
        log.info("companion_tool uid={} courseId={} tool={} args={} elapsedMs={} ok={} cached={} result={}",
                studentId,
                courseId,
                toolName,
                summarize(argsJson, 160),
                elapsedMs,
                result.ok(),
                result.cached(),
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
}
