package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.LearningFrontierService;
import com.wenjin.service.MasteryDecayCalculator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Nodes whose direct prerequisites are all mastered (effective ≥ threshold)
 * but the node itself is not yet mastered — the learning frontier (computed by
 * {@link LearningFrontierService}, shared with the proactive opener).
 */
@Component
public class GetLearningFrontierTool implements CompanionTool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 10;

    private final LearningFrontierService frontierService;
    private final ObjectMapper objectMapper;

    @Autowired
    public GetLearningFrontierTool(LearningFrontierService frontierService, ObjectMapper objectMapper) {
        this.frontierService = Objects.requireNonNull(frontierService, "frontierService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** 便捷构造（单测）：直接由 mapper 组装前沿服务。 */
    public GetLearningFrontierTool(KgNodeMapper nodeMapper,
                                   KgEdgeMapper edgeMapper,
                                   StudentMasteryMapper masteryMapper,
                                   MasteryDecayCalculator decayCalculator,
                                   ObjectMapper objectMapper) {
        this(new LearningFrontierService(nodeMapper, edgeMapper, masteryMapper, decayCalculator), objectMapper);
    }

    @Override
    public String name() {
        return "get_learning_frontier";
    }

    @Override
    public String label() {
        return "正在查找可学前沿…";
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.integer("limit", false, (double) MIN_LIMIT, (double) MAX_LIMIT));
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of(
                "limit", Map.of(
                        "type", "integer",
                        "description", "Max frontier nodes to return (1-10, default 5)")));
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description", "List nodes ready to learn: all prereqs mastered, self not yet.");
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode args) throws ToolException {
        int limit = resolveLimit(args);
        List<Map<String, Object>> items = new ArrayList<>();
        for (LearningFrontierService.FrontierNode n : frontierService.frontier(ctx.studentId(), ctx.courseId(), limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", n.code());
            item.put("name", n.name());
            item.put("effective", n.effective());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        return toJson(body);
    }

    private int resolveLimit(JsonNode args) {
        if (args == null || args.get("limit") == null || args.get("limit").isNull()) {
            return DEFAULT_LIMIT;
        }
        int requested = args.get("limit").asInt(DEFAULT_LIMIT);
        return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, requested));
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize get_learning_frontier result");
        }
    }
}
