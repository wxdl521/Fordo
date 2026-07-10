package com.wenjin.service.companion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
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
    private final ObjectMapper objectMapper;
    private final NodeToolSupport nodeToolSupport;

    public GetNodeDetailTool(KgNodeMapper nodeMapper,
                             KgEdgeMapper edgeMapper,
                             ObjectMapper objectMapper,
                             NodeToolSupport nodeToolSupport) {
        this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
        this.edgeMapper = Objects.requireNonNull(edgeMapper, "edgeMapper");
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
        function.put("description", "Get one node's metadata, prerequisites, and dependents.");
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
        Set<Long> dependentIds = new LinkedHashSet<>();
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
                dependentIds.add(PrereqDirection.dependentNodeId(edge));
            }
        }

        Set<Long> relatedIds = new LinkedHashSet<>();
        relatedIds.addAll(prerequisiteIds);
        relatedIds.addAll(dependentIds);
        Map<Long, KgNode> relatedNodes = relatedIds.isEmpty()
                ? Map.of()
                : nodeMapper.selectBatchIds(relatedIds).stream()
                .filter(node -> Objects.equals(node.getCourseId(), ctx.courseId()))
                .collect(Collectors.toMap(KgNode::getId, Function.identity()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeCode", focus.getNodeCode());
        body.put("name", focus.getName());
        body.put("chapter", focus.getChapter());
        body.put("difficulty", focus.getDifficulty());
        body.put("isKey", focus.getIsKey());
        body.put("bloom", focus.getBloom());
        body.put("description", focus.getDescription());
        body.put("prerequisites", refs(prerequisiteIds, relatedNodes));
        body.put("dependents", refs(dependentIds, relatedNodes));
        return toJson(body);
    }

    private List<Map<String, Object>> refs(Set<Long> ids, Map<Long, KgNode> nodeMap) {
        List<KgNode> nodes = new ArrayList<>();
        for (Long id : ids) {
            KgNode node = nodeMap.get(id);
            if (node != null) {
                nodes.add(node);
            }
        }
        nodes.sort(Comparator.comparing(KgNode::getNodeCode, Comparator.nullsLast(String::compareTo)));
        return nodes.stream()
                .map(nodeToolSupport::nodeRef)
                .toList();
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize get_node_detail result");
        }
    }
}
