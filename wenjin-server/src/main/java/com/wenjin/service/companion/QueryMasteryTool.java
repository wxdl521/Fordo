package com.wenjin.service.companion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class QueryMasteryTool implements CompanionTool {

    private static final DateTimeFormatter EVIDENCE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final KgNodeMapper nodeMapper;
    private final StudentMasteryMapper masteryMapper;
    private final MasteryDecayCalculator decayCalculator;
    private final ObjectMapper objectMapper;
    private final NodeToolSupport nodeToolSupport;

    public QueryMasteryTool(KgNodeMapper nodeMapper,
                            StudentMasteryMapper masteryMapper,
                            MasteryDecayCalculator decayCalculator,
                            ObjectMapper objectMapper,
                            NodeToolSupport nodeToolSupport) {
        this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
        this.masteryMapper = Objects.requireNonNull(masteryMapper, "masteryMapper");
        this.decayCalculator = Objects.requireNonNull(decayCalculator, "decayCalculator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.nodeToolSupport = Objects.requireNonNull(nodeToolSupport, "nodeToolSupport");
    }

    @Override
    public String name() {
        return "query_mastery";
    }

    @Override
    public String label() {
        return "正在查询你的掌握情况…";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of(
                "nodeCode", Map.of(
                        "type", "string",
                        "description", "Optional knowledge node code within the current course"),
                "nodeName", Map.of(
                        "type", "string",
                        "description", "Optional exact knowledge node name within the current course when nodeCode is unknown")));
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description", "Query weak mastery overview or one node's current mastery detail.");
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
        Map<String, Object> body = StringUtils.hasText(nodeCode) || StringUtils.hasText(nodeName)
                ? buildDetail(ctx, nodeCode, nodeName)
                : buildOverview(ctx);
        return toJson(body);
    }

    private Map<String, Object> buildOverview(ToolContext ctx) {
        List<KgNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, ctx.courseId())
                .isNull(KgNode::getArchivedAt));
        List<StudentMastery> masteries = masteryMapper.selectList(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, ctx.studentId())
                .eq(StudentMastery::getCourseId, ctx.courseId()));

        Map<Long, StudentMastery> masteryByNodeId = masteries.stream()
                .collect(Collectors.toMap(StudentMastery::getNodeId, Function.identity(), (left, right) -> left));

        List<MasteryView> items = new ArrayList<>();
        for (KgNode node : nodes) {
            items.add(toMasteryView(node, masteryByNodeId.get(node.getId())));
        }

        List<Map<String, Object>> weakItems = items.stream()
                .filter(item -> item.effectiveLevel() < 2)
                .sorted(Comparator
                        .comparingInt(MasteryView::effectiveLevel)
                        .thenComparingDouble(MasteryView::effectiveScore)
                        .thenComparing(MasteryView::nodeCode))
                .map(MasteryView::toMap)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", weakItems);
        return body;
    }

    private Map<String, Object> buildDetail(ToolContext ctx, String nodeCode, String nodeName) throws ToolException {
        KgNode node = nodeToolSupport.requireNode(ctx.courseId(), nodeCode, nodeName);
        StudentMastery mastery = masteryMapper.selectOne(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, ctx.studentId())
                .eq(StudentMastery::getCourseId, ctx.courseId())
                .eq(StudentMastery::getNodeId, node.getId()));
        return toMasteryView(node, mastery).toMap();
    }

    private MasteryView toMasteryView(KgNode node, StudentMastery mastery) {
        if (mastery == null || mastery.getMasteryScore() == null) {
            return new MasteryView(
                    node.getNodeCode(),
                    node.getName(),
                    0.0,
                    0,
                    0.0,
                    0,
                    null,
                    null);
        }
        double effectiveScore = decayCalculator.effectiveScoreOf(mastery);
        int effectiveLevel = decayCalculator.effectiveLevelOf(effectiveScore);
        Double daysSinceEvidence = mastery.getUpdatedAt() == null ? null : decayCalculator.daysSinceEvidence(mastery.getUpdatedAt());
        return new MasteryView(
                node.getNodeCode(),
                node.getName(),
                mastery.getMasteryScore().doubleValue(),
                mastery.getMasteryLevel() == null ? 0 : mastery.getMasteryLevel(),
                effectiveScore,
                effectiveLevel,
                daysSinceEvidence,
                mastery.getUpdatedAt() == null ? null : EVIDENCE_TIME_FORMAT.format(mastery.getUpdatedAt()));
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize query_mastery result");
        }
    }

    private record MasteryView(String nodeCode,
                               String name,
                               double rawScore,
                               int rawLevel,
                               double effectiveScore,
                               int effectiveLevel,
                               Double daysSinceEvidence,
                               String lastEvidenceAt) {

        private Map<String, Object> toMap() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeCode", nodeCode);
            item.put("name", name);
            item.put("rawScore", rawScore);
            item.put("rawLevel", rawLevel);
            item.put("effectiveScore", effectiveScore);
            item.put("effectiveLevel", effectiveLevel);
            item.put("daysSinceEvidence", daysSinceEvidence);
            item.put("lastEvidenceAt", lastEvidenceAt);
            return item;
        }
    }
}
