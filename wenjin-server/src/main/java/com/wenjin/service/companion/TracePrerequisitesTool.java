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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only BFS over prerequisite edges from a focus node.
 * Direction follows {@link PrereqDirection} (from is prereq of to).
 */
@Component
public class TracePrerequisitesTool implements CompanionTool {

    private static final int DEFAULT_MAX_DEPTH = 3;
    private static final int MIN_DEPTH = 1;
    private static final int MAX_DEPTH = 5;

    private final KgNodeMapper nodeMapper;
    private final KgEdgeMapper edgeMapper;
    private final StudentMasteryMapper masteryMapper;
    private final MasteryDecayCalculator decayCalculator;
    private final ObjectMapper objectMapper;
    private final NodeToolSupport nodeToolSupport;

    public TracePrerequisitesTool(KgNodeMapper nodeMapper,
                                  KgEdgeMapper edgeMapper,
                                  StudentMasteryMapper masteryMapper,
                                  MasteryDecayCalculator decayCalculator,
                                  ObjectMapper objectMapper,
                                  NodeToolSupport nodeToolSupport) {
        this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
        this.edgeMapper = Objects.requireNonNull(edgeMapper, "edgeMapper");
        this.masteryMapper = Objects.requireNonNull(masteryMapper, "masteryMapper");
        this.decayCalculator = Objects.requireNonNull(decayCalculator, "decayCalculator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.nodeToolSupport = Objects.requireNonNull(nodeToolSupport, "nodeToolSupport");
    }

    @Override
    public String name() {
        return "trace_prerequisites";
    }

    @Override
    public String label() {
        return "正在追溯前置知识点…";
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.string("nodeCode", true, 32, ToolParamSpec.NODE_CODE_PATTERN),
                ToolParamSpec.integer("maxDepth", false, (double) MIN_DEPTH, (double) MAX_DEPTH));
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of(
                "nodeCode", Map.of(
                        "type", "string",
                        "description", "Focus knowledge node code within the current course"),
                "maxDepth", Map.of(
                        "type", "integer",
                        "description", "Max prerequisite hop depth (1-5, default 3)")));
        parameters.put("required", List.of("nodeCode"));
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description", "Trace ordered prerequisite chain for a node (read-only BFS).");
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode args) throws ToolException {
        String nodeCode = args == null ? null : args.path("nodeCode").asText(null);
        KgNode focus = nodeToolSupport.requireNode(ctx.courseId(), nodeCode);
        int maxDepth = resolveMaxDepth(args);

        List<KgEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<KgEdge>()
                .eq(KgEdge::getCourseId, ctx.courseId())
                .eq(KgEdge::getRelationType, PrereqDirection.RELATION_TYPE_PREREQUISITE)
                .isNull(KgEdge::getArchivedAt));

        Map<Long, List<Long>> prereqOf = new HashMap<>();
        Set<Long> allRelatedIds = new LinkedHashSet<>();
        for (KgEdge edge : edges) {
            if (!Objects.equals(edge.getCourseId(), ctx.courseId())
                    || !Objects.equals(edge.getRelationType(), PrereqDirection.RELATION_TYPE_PREREQUISITE)
                    || ReviewMarker.isPending(edge.getRelationNote())) {
                continue;
            }
            Long prereqId = PrereqDirection.prerequisiteNodeId(edge);
            Long dependentId = PrereqDirection.dependentNodeId(edge);
            if (prereqId == null || dependentId == null) {
                continue;
            }
            prereqOf.computeIfAbsent(dependentId, k -> new ArrayList<>()).add(prereqId);
            allRelatedIds.add(prereqId);
            allRelatedIds.add(dependentId);
        }
        allRelatedIds.add(focus.getId());

        // BFS by depth; first visit wins (stable shallowest depth).
        List<ChainEntry> chain = new ArrayList<>();
        Queue<Long> queue = new ArrayDeque<>();
        Map<Long, Integer> depthOf = new HashMap<>();
        queue.add(focus.getId());
        depthOf.put(focus.getId(), 0);

        while (!queue.isEmpty()) {
            Long current = queue.poll();
            int depth = depthOf.get(current);
            if (depth >= maxDepth) {
                continue;
            }
            List<Long> prereqs = prereqOf.getOrDefault(current, List.of());
            for (Long prereqId : prereqs) {
                if (depthOf.containsKey(prereqId)) {
                    continue;
                }
                int nextDepth = depth + 1;
                depthOf.put(prereqId, nextDepth);
                chain.add(new ChainEntry(prereqId, nextDepth));
                queue.add(prereqId);
            }
        }

        Map<Long, KgNode> nodesById = loadNodes(allRelatedIds, ctx.courseId());
        Map<Long, StudentMastery> masteryByNodeId = loadMastery(ctx, chain.stream().map(ChainEntry::nodeId).toList());

        List<Map<String, Object>> items = new ArrayList<>();
        chain.sort(Comparator
                .comparingInt(ChainEntry::depth)
                .thenComparing(e -> {
                    KgNode n = nodesById.get(e.nodeId());
                    return n == null || n.getNodeCode() == null ? "" : n.getNodeCode();
                }));
        for (ChainEntry entry : chain) {
            KgNode node = nodesById.get(entry.nodeId());
            if (node == null || node.getArchivedAt() != null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", node.getNodeCode());
            item.put("name", node.getName());
            item.put("effective", effectiveOf(masteryByNodeId.get(node.getId())));
            item.put("depth", entry.depth());
            items.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeCode", focus.getNodeCode());
        body.put("chain", items);
        return toJson(body);
    }

    private int resolveMaxDepth(JsonNode args) {
        if (args == null || args.get("maxDepth") == null || args.get("maxDepth").isNull()) {
            return DEFAULT_MAX_DEPTH;
        }
        int requested = args.get("maxDepth").asInt(DEFAULT_MAX_DEPTH);
        return Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, requested));
    }

    private Map<Long, KgNode> loadNodes(Set<Long> ids, Long courseId) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return nodeMapper.selectBatchIds(ids).stream()
                .filter(n -> Objects.equals(n.getCourseId(), courseId))
                .collect(Collectors.toMap(KgNode::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, StudentMastery> loadMastery(ToolContext ctx, List<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Map.of();
        }
        List<StudentMastery> rows = masteryMapper.selectList(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, ctx.studentId())
                .eq(StudentMastery::getCourseId, ctx.courseId())
                .in(StudentMastery::getNodeId, nodeIds));
        return rows.stream()
                .collect(Collectors.toMap(StudentMastery::getNodeId, Function.identity(), (a, b) -> a));
    }

    private Double effectiveOf(StudentMastery mastery) {
        if (mastery == null || mastery.getMasteryScore() == null) {
            return null;
        }
        return decayCalculator.effectiveScoreOf(mastery);
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize trace_prerequisites result");
        }
    }

    private record ChainEntry(Long nodeId, int depth) {
    }
}
