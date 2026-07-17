package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.ReviewTodayVO;
import com.wenjin.dto.ReviewTodayVO.ReviewItemVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.ReviewService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Today's review queue — same base data as the side-drawer {@link ReviewService#getToday},
 * merged with any same-day pins from {@link ReviewScheduleStore}.
 */
@Component
public class GetReviewQueueTool implements CompanionTool {

    private final ReviewService reviewService;
    private final ReviewScheduleStore scheduleStore;
    private final KgNodeMapper nodeMapper;
    private final StudentMasteryMapper masteryMapper;
    private final MasteryDecayCalculator decayCalculator;
    private final ObjectMapper objectMapper;

    public GetReviewQueueTool(ReviewService reviewService,
                              ReviewScheduleStore scheduleStore,
                              KgNodeMapper nodeMapper,
                              StudentMasteryMapper masteryMapper,
                              MasteryDecayCalculator decayCalculator,
                              ObjectMapper objectMapper) {
        this.reviewService = Objects.requireNonNull(reviewService, "reviewService");
        this.scheduleStore = Objects.requireNonNull(scheduleStore, "scheduleStore");
        this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
        this.masteryMapper = Objects.requireNonNull(masteryMapper, "masteryMapper");
        this.decayCalculator = Objects.requireNonNull(decayCalculator, "decayCalculator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return "get_review_queue";
    }

    @Override
    public String label() {
        return "正在读取今日复习队列…";
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of();
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of());
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description", "Get today's review queue (same data as the review side drawer).");
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode args) throws ToolException {
        ReviewTodayVO today = reviewService.getToday(ctx.studentId(), ctx.courseId());
        List<ReviewItemVO> baseItems = today == null || today.getItems() == null
                ? List.of()
                : today.getItems();

        Set<String> presentCodes = new LinkedHashSet<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (ReviewItemVO item : baseItems) {
            if (item == null || item.getNodeCode() == null) {
                continue;
            }
            presentCodes.add(item.getNodeCode());
            items.add(toItemMap(item, false, null));
        }

        Map<String, ReviewScheduleStore.Mode> forced = scheduleStore.forcedToday(ctx.studentId(), ctx.courseId());
        for (Map.Entry<String, ReviewScheduleStore.Mode> entry : forced.entrySet()) {
            String code = entry.getKey();
            if (presentCodes.contains(code)) {
                continue;
            }
            Map<String, Object> forcedItem = loadForcedItem(ctx, code, entry.getValue());
            if (forcedItem != null) {
                items.add(forcedItem);
                presentCodes.add(code);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("totalDecayingCount", today == null ? 0 : today.getTotalDecayingCount());
        body.put("forcedCount", forced.size());
        return toJson(body);
    }

    private Map<String, Object> toItemMap(ReviewItemVO item, boolean forced, String mode) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeCode", item.getNodeCode());
        map.put("name", item.getName());
        map.put("chapter", item.getChapter());
        map.put("rawScore", item.getRawScore());
        map.put("effectiveScore", item.getEffectiveScore());
        map.put("effectiveLevel", item.getEffectiveLevel());
        map.put("daysSinceEvidence", item.getDaysSinceEvidence());
        map.put("urgency", item.getUrgency());
        map.put("availableQuestions", item.getAvailableQuestions());
        map.put("forced", forced);
        if (mode != null) {
            map.put("mode", mode);
        }
        return map;
    }

    private Map<String, Object> loadForcedItem(ToolContext ctx, String nodeCode, ReviewScheduleStore.Mode mode) {
        KgNode node = nodeMapper.selectOne(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, ctx.courseId())
                .eq(KgNode::getNodeCode, nodeCode)
                .isNull(KgNode::getArchivedAt));
        if (node == null) {
            return null;
        }
        StudentMastery mastery = masteryMapper.selectOne(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, ctx.studentId())
                .eq(StudentMastery::getCourseId, ctx.courseId())
                .eq(StudentMastery::getNodeId, node.getId()));

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeCode", node.getNodeCode());
        map.put("name", node.getName());
        map.put("chapter", node.getChapter());
        if (mastery != null && mastery.getMasteryScore() != null) {
            double raw = mastery.getMasteryScore().doubleValue();
            double effective = decayCalculator.effectiveScoreOf(mastery);
            map.put("rawScore", raw);
            map.put("effectiveScore", effective);
            map.put("effectiveLevel", decayCalculator.effectiveLevelOf(effective));
            map.put("daysSinceEvidence", decayCalculator.daysSinceEvidence(mastery.getUpdatedAt()));
        } else {
            map.put("rawScore", 0.0);
            map.put("effectiveScore", 0.0);
            map.put("effectiveLevel", 0);
            map.put("daysSinceEvidence", null);
        }
        map.put("urgency", null);
        map.put("availableQuestions", null);
        map.put("forced", true);
        map.put("mode", mode.name());
        return map;
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize get_review_queue result");
        }
    }
}
