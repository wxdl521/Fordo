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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class GetNodeDetailTool implements CompanionTool {

    private final KgNodeMapper nodeMapper;
    private final KgEdgeMapper edgeMapper;
    private final StudentMasteryMapper masteryMapper;
    private final MasteryDecayCalculator decayCalculator;
    private final ObjectMapper objectMapper;
    private final NodeToolSupport nodeToolSupport;

    public GetNodeDetailTool(KgNodeMapper nodeMapper,
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
        return "get_node_detail";
    }

    @Override
    public String label() {
        return "正在读取知识点详情…";
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.string("nodeCode", false, 32, ToolParamSpec.NODE_CODE_PATTERN),
                ToolParamSpec.string("nodeName", false, 128, null));
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of(
                "nodeCode", Map.of(
                        "type", "string",
                        "description", "Knowledge node code within the current course"),
                "nodeName", Map.of(
                        "type", "string",
                        "description", "Exact knowledge node name within the current course when nodeCode is unknown")));
        parameters.put("anyOf", List.of(
                Map.of("required", List.of("nodeCode")),
                Map.of("required", List.of("nodeName"))));
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description", "Get one node's metadata, prerequisites, and successors with mastery.");
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode args) throws ToolException {
        String nodeCode = args == null ? null : args.path("nodeCode").asText(null);
        String nodeName = args == null ? null : args.path("nodeName").asText(null);
        KgNode focus = nodeToolSupport.requireNode(ctx.courseId(), nodeCode, nodeName);

        List<KgEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<KgEdge>()
                .eq(KgEdge::getCourseId, ctx.courseId())
                .eq(KgEdge::getRelationType, PrereqDirection.RELATION_TYPE_PREREQUISITE)
                .isNull(KgEdge::getArchivedAt));

        Set<Long> prerequisiteIds = new LinkedHashSet<>();
        Set<Long> successorIds = new LinkedHashSet<>();
        for (KgEdge edge : edges) {
            if (!Objects.equals(edge.getCourseId(), ctx.courseId())
                    || !Objects.equals(edge.getRelationType(), PrereqDirection.RELATION_TYPE_PREREQUISITE)
                    || ReviewMarker.isPending(edge.getRelationNote())) {
                continue;
            }
            if (Objects.equals(PrereqDirection.dependentNodeId(edge), focus.getId())) {
                prerequisiteIds.add(PrereqDirection.prerequisiteNodeId(edge));
            }
            if (Objects.equals(PrereqDirection.prerequisiteNodeId(edge), focus.getId())) {
                successorIds.add(PrereqDirection.dependentNodeId(edge));
            }
        }

        Set<Long> relatedIds = new LinkedHashSet<>();
        relatedIds.add(focus.getId());
        relatedIds.addAll(prerequisiteIds);
        relatedIds.addAll(successorIds);
        Map<Long, KgNode> relatedNodes = relatedIds.isEmpty()
                ? Map.of()
                : nodeMapper.selectBatchIds(relatedIds).stream()
                .filter(node -> Objects.equals(node.getCourseId(), ctx.courseId()))
                .collect(Collectors.toMap(KgNode::getId, Function.identity()));

        Map<Long, Double> effectiveByNodeId = loadEffective(ctx, relatedIds);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("nodeCode", focus.getNodeCode());
        detail.put("name", focus.getName());
        detail.put("chapter", focus.getChapter());
        detail.put("difficulty", focus.getDifficulty());
        detail.put("isKey", focus.getIsKey());
        detail.put("bloom", focus.getBloom());
        detail.put("description", focus.getDescription());
        detail.put("effective", effectiveByNodeId.get(focus.getId()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", detail);
        // Flat fields kept for existing consumers / prompt readability.
        body.put("nodeCode", focus.getNodeCode());
        body.put("name", focus.getName());
        body.put("chapter", focus.getChapter());
        body.put("difficulty", focus.getDifficulty());
        body.put("isKey", focus.getIsKey());
        body.put("bloom", focus.getBloom());
        body.put("description", focus.getDescription());
        body.put("prerequisites", refs(prerequisiteIds, relatedNodes, effectiveByNodeId));
        body.put("successors", refs(successorIds, relatedNodes, effectiveByNodeId));
        return toJson(body);
    }

    private Map<Long, Double> loadEffective(ToolContext ctx, Set<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Map.of();
        }
        List<StudentMastery> rows = masteryMapper.selectList(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, ctx.studentId())
                .eq(StudentMastery::getCourseId, ctx.courseId())
                .in(StudentMastery::getNodeId, nodeIds));
        Map<Long, Double> map = new LinkedHashMap<>();
        for (StudentMastery m : rows) {
            if (m.getMasteryScore() == null) {
                continue;
            }
            map.put(m.getNodeId(), decayCalculator.effectiveScoreOf(m));
        }
        return map;
    }

    private List<Map<String, Object>> refs(Set<Long> ids,
                                           Map<Long, KgNode> nodeMap,
                                           Map<Long, Double> effectiveByNodeId) {
        List<KgNode> nodes = new ArrayList<>();
        for (Long id : ids) {
            KgNode node = nodeMap.get(id);
            if (node != null) {
                nodes.add(node);
            }
        }
        nodes.sort(Comparator.comparing(KgNode::getNodeCode, Comparator.nullsLast(String::compareTo)));
        List<Map<String, Object>> refs = new ArrayList<>();
        for (KgNode node : nodes) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("code", node.getNodeCode());
            ref.put("name", node.getName());
            ref.put("effective", effectiveByNodeId.get(node.getId()));
            refs.add(ref);
        }
        return refs;
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize get_node_detail result");
        }
    }
}
