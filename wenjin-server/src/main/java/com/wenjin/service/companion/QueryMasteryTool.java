package com.wenjin.service.companion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.MasterySnapshot;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.MasterySnapshotMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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
    private static final double TREND_THRESHOLD = 0.05;
    private static final int MAX_NODE_CODES = 10;

    private final KgNodeMapper nodeMapper;
    private final StudentMasteryMapper masteryMapper;
    private final MasterySnapshotMapper snapshotMapper;
    private final MasteryDecayCalculator decayCalculator;
    private final ObjectMapper objectMapper;
    private final NodeToolSupport nodeToolSupport;

    public QueryMasteryTool(KgNodeMapper nodeMapper,
                            StudentMasteryMapper masteryMapper,
                            MasterySnapshotMapper snapshotMapper,
                            MasteryDecayCalculator decayCalculator,
                            ObjectMapper objectMapper,
                            NodeToolSupport nodeToolSupport) {
        this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
        this.masteryMapper = Objects.requireNonNull(masteryMapper, "masteryMapper");
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper");
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
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.string("nodeCode", false, 32, ToolParamSpec.NODE_CODE_PATTERN),
                ToolParamSpec.string("nodeName", false, 128, null),
                ToolParamSpec.array("nodeCodes", false));
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("nodeCode", Map.of(
                "type", "string",
                "description", "Optional knowledge node code within the current course"));
        properties.put("nodeName", Map.of(
                "type", "string",
                "description", "Optional exact knowledge node name within the current course when nodeCode is unknown"));
        properties.put("nodeCodes", Map.of(
                "type", "array",
                "description", "Optional list of node codes (max 10). Empty/absent falls back to session focus or overview.",
                "items", Map.of("type", "string"),
                "maxItems", MAX_NODE_CODES));
        parameters.put("properties", properties);
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description",
                "Query weak mastery overview, one node, or up to 10 nodes; includes trend and lastPracticedAt.");
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode args) throws ToolException {
        List<String> nodeCodes = extractNodeCodes(args);
        String nodeCode = args == null ? null : args.path("nodeCode").asText(null);
        String nodeName = args == null ? null : args.path("nodeName").asText(null);

        if (!nodeCodes.isEmpty()) {
            if (nodeCodes.size() > MAX_NODE_CODES) {
                throw new ToolException("nodeCodes exceeds max of " + MAX_NODE_CODES);
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (String code : nodeCodes) {
                KgNode node = nodeToolSupport.requireNode(ctx.courseId(), code);
                items.add(buildDetailMap(ctx, node));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", items);
            return toJson(body);
        }

        if (StringUtils.hasText(nodeCode) || StringUtils.hasText(nodeName)) {
            KgNode node = nodeToolSupport.requireNode(ctx.courseId(), nodeCode, nodeName);
            return toJson(buildDetailMap(ctx, node));
        }

        if (StringUtils.hasText(ctx.focusNodeCode())) {
            KgNode node = nodeToolSupport.requireNode(ctx.courseId(), ctx.focusNodeCode());
            return toJson(buildDetailMap(ctx, node));
        }

        return toJson(buildOverview(ctx));
    }

    private List<String> extractNodeCodes(JsonNode args) {
        if (args == null || !args.has("nodeCodes") || args.get("nodeCodes").isNull()) {
            return List.of();
        }
        JsonNode array = args.get("nodeCodes");
        if (!array.isArray()) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (JsonNode item : array) {
            if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                codes.add(item.asText().trim());
            }
        }
        return codes;
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
            items.add(toMasteryView(ctx, node, masteryByNodeId.get(node.getId())));
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

    private Map<String, Object> buildDetailMap(ToolContext ctx, KgNode node) {
        StudentMastery mastery = masteryMapper.selectOne(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, ctx.studentId())
                .eq(StudentMastery::getCourseId, ctx.courseId())
                .eq(StudentMastery::getNodeId, node.getId()));
        return toMasteryView(ctx, node, mastery).toMap();
    }

    private MasteryView toMasteryView(ToolContext ctx, KgNode node, StudentMastery mastery) {
        List<MasterySnapshot> lastTwo = loadLastTwoSnapshots(ctx, node.getId());
        String trend = computeTrend(lastTwo);
        String lastPracticedAt = resolveLastPracticedAt(mastery, lastTwo);

        if (mastery == null || mastery.getMasteryScore() == null) {
            return new MasteryView(
                    node.getNodeCode(),
                    node.getName(),
                    0.0,
                    0,
                    0.0,
                    0,
                    null,
                    null,
                    trend,
                    lastPracticedAt);
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
                mastery.getUpdatedAt() == null ? null : EVIDENCE_TIME_FORMAT.format(mastery.getUpdatedAt()),
                trend,
                lastPracticedAt);
    }

    private List<MasterySnapshot> loadLastTwoSnapshots(ToolContext ctx, Long nodeId) {
        List<MasterySnapshot> rows = snapshotMapper.selectList(new LambdaQueryWrapper<MasterySnapshot>()
                .eq(MasterySnapshot::getStudentId, ctx.studentId())
                .eq(MasterySnapshot::getCourseId, ctx.courseId())
                .eq(MasterySnapshot::getNodeId, nodeId)
                .orderByDesc(MasterySnapshot::getSnapshotAt)
                .orderByDesc(MasterySnapshot::getId)
                .last("LIMIT 2"));
        return rows == null ? List.of() : rows;
    }

    /**
     * Compare last two mastery_snapshot scores. Delta &gt; +threshold → UP;
     * &lt; −threshold → DOWN; otherwise FLAT (including fewer than two samples).
     */
    static String computeTrend(List<MasterySnapshot> lastTwoNewestFirst) {
        if (lastTwoNewestFirst == null || lastTwoNewestFirst.size() < 2) {
            return "FLAT";
        }
        MasterySnapshot newest = lastTwoNewestFirst.get(0);
        MasterySnapshot previous = lastTwoNewestFirst.get(1);
        if (newest.getMasteryScore() == null || previous.getMasteryScore() == null) {
            return "FLAT";
        }
        double delta = newest.getMasteryScore().doubleValue() - previous.getMasteryScore().doubleValue();
        if (delta > TREND_THRESHOLD) {
            return "UP";
        }
        if (delta < -TREND_THRESHOLD) {
            return "DOWN";
        }
        return "FLAT";
    }

    private String resolveLastPracticedAt(StudentMastery mastery, List<MasterySnapshot> lastTwoNewestFirst) {
        LocalDateTime latest = null;
        if (lastTwoNewestFirst != null && !lastTwoNewestFirst.isEmpty()) {
            latest = lastTwoNewestFirst.get(0).getSnapshotAt();
        }
        if (mastery != null && mastery.getUpdatedAt() != null) {
            if (latest == null || mastery.getUpdatedAt().isAfter(latest)) {
                latest = mastery.getUpdatedAt();
            }
        }
        return latest == null ? null : EVIDENCE_TIME_FORMAT.format(latest);
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
                               String lastEvidenceAt,
                               String trend,
                               String lastPracticedAt) {

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
            item.put("trend", trend);
            item.put("lastPracticedAt", lastPracticedAt);
            return item;
        }
    }
}
