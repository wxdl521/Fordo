package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.service.memory.StudentMemoryService;
import com.wenjin.support.MemoryKind;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 写入 SEMANTIC / PROCEDURAL 观察记忆（Jaccard 去重；写配额由 ToolGovernor 控制）。
 */
@Component
public class RecordObservationTool implements CompanionTool {

    private static final List<String> KINDS = List.of("SEMANTIC", "PROCEDURAL");

    private final StudentMemoryService memoryService;
    private final FeatureFlagsProperties featureFlags;
    private final ObjectMapper objectMapper;

    public RecordObservationTool(StudentMemoryService memoryService,
                                 FeatureFlagsProperties featureFlags,
                                 ObjectMapper objectMapper) {
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService");
        this.featureFlags = featureFlags;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return "record_observation";
    }

    @Override
    public String label() {
        return "正在记录学习观察…";
    }

    @Override
    public boolean sideEffecting() {
        return true;
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.string("content", true, 200, null),
                new ToolParamSpec(
                        "kind",
                        ToolParamSpec.ParamType.STRING,
                        true,
                        null,
                        null,
                        null,
                        KINDS,
                        null),
                ToolParamSpec.number("salience", false, 0.5, 1.5));
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("content", Map.of(
                "type", "string",
                "description", "Observation content (max 200 chars)",
                "maxLength", 200));
        properties.put("kind", Map.of(
                "type", "string",
                "enum", KINDS,
                "description", "SEMANTIC preference/fact or PROCEDURAL strategy"));
        properties.put("salience", Map.of(
                "type", "number",
                "minimum", 0.5,
                "maximum", 1.5,
                "description", "Base salience (default 1.0)"));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("content", "kind"));
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description",
                "Record a durable SEMANTIC or PROCEDURAL observation about the current student.");
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    @Override
    public ToolExecutionResult executeForGovernor(String toolCallId,
                                                  ToolContext ctx,
                                                  JsonNode args) throws ToolException {
        if (!memoryEnabled()) {
            return notAllowed(toolCallId);
        }
        return CompanionTool.super.executeForGovernor(toolCallId, ctx, args);
    }

    @Override
    public String execute(ToolContext ctx, JsonNode args) throws ToolException {
        if (!memoryEnabled()) {
            throw new ToolException("记忆功能未开启");
        }
        String content = args == null ? null : args.path("content").asText(null);
        String kindRaw = args == null ? null : args.path("kind").asText(null);
        if (!StringUtils.hasText(content)) {
            throw new ToolException("content is required");
        }
        int kind = parseKind(kindRaw);
        BigDecimal salience = BigDecimal.ONE;
        if (args != null && args.has("salience") && !args.get("salience").isNull()) {
            salience = BigDecimal.valueOf(args.get("salience").asDouble());
        }

        Long id = memoryService.upsertOrReinforce(
                ctx.studentId(),
                ctx.courseId(),
                kind,
                content.trim(),
                null,
                null,
                salience);
        if (id == null) {
            throw new ToolException("Failed to record observation");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("kind", kindRaw == null ? null : kindRaw.trim().toUpperCase());
        body.put("content", content.trim().length() > 200
                ? content.trim().substring(0, 200)
                : content.trim());
        body.put("recorded", true);
        return toJson(body);
    }

    private boolean memoryEnabled() {
        return featureFlags == null || featureFlags.isMemoryEnabled();
    }

    private static int parseKind(String raw) throws ToolException {
        if (!StringUtils.hasText(raw)) {
            throw new ToolException("kind is required");
        }
        return switch (raw.trim().toUpperCase()) {
            case "SEMANTIC" -> MemoryKind.SEMANTIC;
            case "PROCEDURAL" -> MemoryKind.PROCEDURAL;
            default -> throw new ToolException("kind must be SEMANTIC or PROCEDURAL");
        };
    }

    private ToolExecutionResult notAllowed(String toolCallId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", ToolErrorCodes.TOOL_NOT_ALLOWED);
        body.put("message", "记忆功能未开启");
        body.put("retryable", false);
        try {
            return new ToolExecutionResult(
                    toolCallId, name(), false, objectMapper.writeValueAsString(body), null, true, false);
        } catch (JsonProcessingException e) {
            return new ToolExecutionResult(
                    toolCallId, name(), false,
                    "{\"ok\":false,\"code\":\"TOOL_NOT_ALLOWED\",\"message\":\"记忆功能未开启\",\"retryable\":false}",
                    null, true, false);
        }
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize record_observation result");
        }
    }
}
