package com.wenjin.service.companion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.support.PrereqDirection;
import com.wenjin.support.ReviewMarker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Nodes whose direct prerequisites are all mastered (effective ≥ threshold)
 * but the node itself is not yet mastered — the learning frontier.
 */
@Component
public class GetLearningFrontierTool implements CompanionTool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 10;

    private final KgNodeMapper nodeMapper;
    private final KgEdgeMapper edgeMapper;
    private final StudentMasteryMapper masteryMapper;
    private final MasteryDecayCalculator decayCalculator;
    private final ObjectMapper objectMapper;

    public GetLearningFrontierTool(KgNodeMapper nodeMapper,
                                   KgEdgeMapper edgeMapper,
                                   StudentMasteryMapper masteryMapper,
                                   MasteryDecayCalculator decayCalculator,
                                   ObjectMapper objectMapper) {
        this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
        this.edgeMapper = Objects.requireNonNull(edgeMapper, "edgeMapper");
        this.masteryMapper = Objects.requireNonNull(masteryMapper, "masteryMapper");
        this.decayCalculator = Objects.requireNonNull(decayCalculator, "decayCalculator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
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

        List<KgNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, ctx.courseId())
                .isNull(KgNode::getArchivedAt));
        Map<Long, KgNode> nodeById = nodes.stream()
                .collect(Collectors.toMap(KgNode::getId, Function.identity(), (a, b) -> a));

        List<StudentMastery> masteries = masteryMapper.selectList(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, ctx.studentId())
                .eq(StudentMastery::getCourseId, ctx.courseId()));
        Map<Long, Double> effectiveByNodeId = new HashMap<>();
        for (StudentMastery m : masteries) {
            if (m.getMasteryScore() == null) {
                continue;
            }
            effectiveByNodeId.put(m.getNodeId(), decayCalculator.effectiveScoreOf(m));
        }

        List<KgEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<KgEdge>()
                .eq(KgEdge::getCourseId, ctx.courseId())
                .eq(KgEdge::getRelationType, PrereqDirection.RELATION_TYPE_PREREQUISITE)
                .isNull(KgEdge::getArchivedAt));
        Map<Long, List<Long>> prereqOf = new HashMap<>();
        for (KgEdge edge : edges) {
            if (ReviewMarker.isPending(edge.getRelationNote())) {
                continue;
            }
            Long prereqId = PrereqDirection.prerequisiteNodeId(edge);
            Long dependentId = PrereqDirection.dependentNodeId(edge);
            if (prereqId == null || dependentId == null) {
                continue;
            }
            if (!nodeById.containsKey(prereqId) || !nodeById.containsKey(dependentId)) {
                continue;
            }
            prereqOf.computeIfAbsent(dependentId, k -> new ArrayList<>()).add(prereqId);
        }

        double threshold = decayCalculator.getMasteredThreshold();
        List<Map<String, Object>> frontier = new ArrayList<>();
        for (KgNode node : nodes) {
            double selfEffective = effectiveByNodeId.getOrDefault(node.getId(), 0.0);
            if (selfEffective >= threshold) {
                continue;
            }
            List<Long> prereqs = prereqOf.getOrDefault(node.getId(), List.of());
            boolean allPrereqsMastered = true;
            for (Long prereqId : prereqs) {
                double pe = effectiveByNodeId.getOrDefault(prereqId, 0.0);
                if (pe < threshold) {
                    allPrereqsMastered = false;
                    break;
                }
            }
            if (!allPrereqsMastered) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", node.getNodeCode());
            item.put("name", node.getName());
            item.put("effective", selfEffective);
            frontier.add(item);
        }

        frontier.sort(Comparator
                .comparing((Map<String, Object> m) -> (Double) m.get("effective"))
                .thenComparing(m -> String.valueOf(m.get("code"))));

        if (frontier.size() > limit) {
            frontier = new ArrayList<>(frontier.subList(0, limit));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", frontier);
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
