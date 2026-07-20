package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.entity.StudentMemory;
import com.wenjin.service.memory.StudentMemoryService;
import com.wenjin.support.MemoryKind;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 只读检索学生本人记忆（LIKE + kind + effective）；命中强化。
 */
@Component
public class RecallStudentContextTool implements CompanionTool {

    private static final List<String> KINDS = List.of("EPISODIC", "SEMANTIC", "PROCEDURAL");

    private final StudentMemoryService memoryService;
    private final FeatureFlagsProperties featureFlags;
    private final ObjectMapper objectMapper;

    public RecallStudentContextTool(StudentMemoryService memoryService,
                                    FeatureFlagsProperties featureFlags,
                                    ObjectMapper objectMapper) {
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService");
        this.featureFlags = featureFlags;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return "recall_student_context";
    }

    @Override
    public String label() {
        return "正在回忆你的学习记忆…";
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.string("query", true, 50, null),
                new ToolParamSpec(
                        "kind",
                        ToolParamSpec.ParamType.STRING,
                        false,
                        null,
                        null,
                        null,
                        KINDS,
                        null),
                ToolParamSpec.integer("topK", false, 1.0, 10.0));
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "Substring to search in memory content (max 50 chars)",
                "maxLength", 50));
        properties.put("kind", Map.of(
                "type", "string",
                "enum", KINDS,
                "description", "Optional memory kind filter"));
        properties.put("topK", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 10,
                "description", "Max hits to return (default 5)"));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("query"));
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description",
                "Recall the current student's long-term learning memories by keyword; self only.");
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
        String query = args == null ? null : args.path("query").asText(null);
        if (!StringUtils.hasText(query)) {
            throw new ToolException("query is required");
        }
        Integer kind = parseKind(args == null ? null : args.path("kind").asText(null));
        int topK = 5;
        if (args != null && args.has("topK") && !args.get("topK").isNull()) {
            topK = args.get("topK").asInt(5);
        }
        topK = Math.max(1, Math.min(10, topK));

        List<StudentMemory> hits = memoryService.searchAndReinforce(
                ctx.studentId(), ctx.courseId(), query.trim(), kind, topK);

        List<Map<String, Object>> items = new ArrayList<>();
        for (StudentMemory m : hits) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", m.getId());
            item.put("kind", kindName(m.getKind()));
            item.put("content", m.getContent());
            item.put("nodeCode", m.getNodeCode());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("count", items.size());
        return toJson(body);
    }

    private boolean memoryEnabled() {
        return featureFlags == null || featureFlags.isMemoryEnabled();
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

    private static Integer parseKind(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return switch (raw.trim().toUpperCase()) {
            case "EPISODIC" -> MemoryKind.EPISODIC;
            case "SEMANTIC" -> MemoryKind.SEMANTIC;
            case "PROCEDURAL" -> MemoryKind.PROCEDURAL;
            default -> null;
        };
    }

    private static String kindName(Integer kind) {
        if (kind == null) {
            return "EPISODIC";
        }
        return switch (kind) {
            case MemoryKind.SEMANTIC -> "SEMANTIC";
            case MemoryKind.PROCEDURAL -> "PROCEDURAL";
            default -> "EPISODIC";
        };
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize recall_student_context result");
        }
    }
}
