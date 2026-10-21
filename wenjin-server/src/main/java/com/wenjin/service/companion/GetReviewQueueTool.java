package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.ReviewTodayVO;
import com.wenjin.dto.ReviewTodayVO.ReviewItemVO;
import com.wenjin.service.ReviewService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Today's review queue — same payload as the side-drawer {@link ReviewService#getToday}
 * (decay list already merged with durable schedule_review pins).
 */
@Component
public class GetReviewQueueTool implements CompanionTool {

    private final ReviewService reviewService;
    private final ObjectMapper objectMapper;

    public GetReviewQueueTool(ReviewService reviewService, ObjectMapper objectMapper) {
        this.reviewService = Objects.requireNonNull(reviewService, "reviewService");
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

        int forcedCount = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (ReviewItemVO item : baseItems) {
            if (item == null) {
                continue;
            }
            boolean forced = Boolean.TRUE.equals(item.getForced());
            if (forced) {
                forcedCount++;
            }
            items.add(toItemMap(item, forced, item.getPinMode()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("totalDecayingCount", today == null ? 0 : today.getTotalDecayingCount());
        body.put("forcedCount", forcedCount);
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

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize get_review_queue result");
        }
    }
}
