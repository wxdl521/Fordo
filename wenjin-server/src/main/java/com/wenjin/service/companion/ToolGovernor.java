package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.CompanionToolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

        if (state == null || !state.tryConsume()) {
            result = failed(toolCallId, toolName, "tool_budget_exceeded", "Tool call budget exceeded");
            audit(ctx, toolName, argsJson, started, result);
            return result;
        }

        result = executeWithTimeout(ctx, call, tool, args);
        audit(ctx, toolName, argsJson, started, result);
        return result;
    }

    private ToolExecutionResult executeWithTimeout(ToolContext ctx,
                                                   CompanionAiClient.ToolCall call,
                                                   CompanionTool tool,
                                                   JsonNode args) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<String> future = executor.submit(() -> tool.execute(ctx, args));
        try {
            String content = future.get(timeoutMs(), TimeUnit.MILLISECONDS);
            return new ToolExecutionResult(call.id(), call.name(), true, truncate(content));
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

    private String truncate(String content) {
        String safe = content == null ? "" : content;
        int max = properties == null ? 2000 : properties.getMaxResultChars();
        max = Math.max(0, max);
        if (safe.length() <= max) {
            return safe;
        }
        return safe.substring(0, max);
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
}
