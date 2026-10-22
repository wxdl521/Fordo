package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.service.rag.RetrievalHit;
import com.wenjin.service.rag.RetrievalService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 跨节点课程资料检索（读档，可缓存；M8）。
 * <p>params：query STRING≤50 必填，nodeCodes ARRAY≤5 可空，topK INT 1..5 默认 3。
 */
@Component
public class SearchMaterialsTool implements CompanionTool {

    public static final String NAME = "search_materials";

    private final RetrievalService retrievalService;
    private final FeatureFlagsProperties featureFlags;
    private final LimitsProperties limits;
    private final ObjectMapper objectMapper;

    public SearchMaterialsTool(RetrievalService retrievalService,
                               FeatureFlagsProperties featureFlags,
                               LimitsProperties limits,
                               ObjectMapper objectMapper) {
        this.retrievalService = Objects.requireNonNull(retrievalService, "retrievalService");
        this.featureFlags = featureFlags;
        this.limits = limits == null ? new LimitsProperties() : limits;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String label() {
        return "正在检索课程资料…";
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.string("query", true, 50, null),
                ToolParamSpec.array("nodeCodes", false),
                ToolParamSpec.integer("topK", false, 1.0, 5.0));
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "Search query over course materials (max 50 chars)",
                "maxLength", 50));
        properties.put("nodeCodes", Map.of(
                "type", "array",
                "description", "Optional node scope (max 5 codes)",
                "maxItems", 5,
                "items", Map.of("type", "string")));
        properties.put("topK", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 5,
                "description", "Max hits (default 3)"));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("query"));
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description",
                "Search course materials (RAG chunks) by keyword; optional node scope; returns snippets with citations.");
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
        if (!ragEnabled()) {
            return notAllowed(toolCallId);
        }
        return CompanionTool.super.executeForGovernor(toolCallId, ctx, args);
    }

    @Override
    public String execute(ToolContext ctx, JsonNode args) throws ToolException {
        if (!ragEnabled()) {
            throw new ToolException("资料检索功能未开启");
        }
        String query = args == null ? null : args.path("query").asText(null);
        if (!StringUtils.hasText(query)) {
            throw new ToolException("query is required");
        }
        if (query.length() > 50) {
            query = query.substring(0, 50);
        }
        List<String> nodeCodes = parseNodeCodes(args == null ? null : args.get("nodeCodes"));
        int topK = limits.getRag().getSearchDefaultTopK();
        if (args != null && args.has("topK") && !args.get("topK").isNull()) {
            topK = args.get("topK").asInt(topK);
        }
        topK = Math.max(1, Math.min(5, topK));
        int snippetChars = Math.max(40, limits.getRag().getSnippetChars());

        RetrievalService.RetrievalResult result = retrievalService.search(
                ctx.courseId(), nodeCodes, query.trim(), topK, true);

        List<Map<String, Object>> items = new ArrayList<>();
        for (RetrievalHit hit : result.hits()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", hit.chunkId());
            item.put("snippet", snippet(hit.content(), snippetChars));
            item.put("sourceTitle", hit.sourceTitle());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("count", items.size());
        body.put("scopeWidened", result.scopeWidened());
        return toJson(body);
    }

    private static List<String> parseNodeCodes(JsonNode node) throws ToolException {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ToolException("nodeCodes must be an array");
        }
        if (node.size() > 5) {
            throw new ToolException("nodeCodes exceeds max size 5");
        }
        List<String> codes = new ArrayList<>();
        for (JsonNode el : node) {
            if (el != null && el.isTextual() && StringUtils.hasText(el.asText())) {
                codes.add(el.asText().trim());
            }
        }
        return codes;
    }

    private static String snippet(String content, int maxChars) {
        if (content == null) {
            return "";
        }
        String t = content.trim().replaceAll("\\s+", " ");
        if (t.length() <= maxChars) {
            return t;
        }
        return t.substring(0, maxChars);
    }

    private boolean ragEnabled() {
        return featureFlags == null || featureFlags.isRagEnabled();
    }

    private ToolExecutionResult notAllowed(String toolCallId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", ToolErrorCodes.TOOL_NOT_ALLOWED);
        body.put("message", "资料检索功能未开启");
        body.put("retryable", false);
        try {
            return new ToolExecutionResult(
                    toolCallId, name(), false, objectMapper.writeValueAsString(body), null, true, false);
        } catch (JsonProcessingException e) {
            return new ToolExecutionResult(
                    toolCallId, name(), false,
                    "{\"ok\":false,\"code\":\"TOOL_NOT_ALLOWED\",\"message\":\"资料检索功能未开启\",\"retryable\":false}",
                    null, true, false);
        }
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize search_materials result");
        }
    }
}
