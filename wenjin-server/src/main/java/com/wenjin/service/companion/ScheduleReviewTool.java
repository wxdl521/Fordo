package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.entity.KgNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pin a knowledge node onto today's review queue.
 *
 * <p>Uses {@link ReviewScheduleStore} (in-memory ConcurrentHashMap). Persistence is deferred
 * for M5 — see store javadoc. Same student/course/day/node pin is idempotent.
 */
@Component
public class ScheduleReviewTool implements CompanionTool {

    private static final List<String> MODES = List.of("ADVANCE", "TODAY");

    private final ReviewScheduleStore scheduleStore;
    private final NodeToolSupport nodeToolSupport;
    private final ObjectMapper objectMapper;

    public ScheduleReviewTool(ReviewScheduleStore scheduleStore,
                              NodeToolSupport nodeToolSupport,
                              ObjectMapper objectMapper) {
        this.scheduleStore = Objects.requireNonNull(scheduleStore, "scheduleStore");
        this.nodeToolSupport = Objects.requireNonNull(nodeToolSupport, "nodeToolSupport");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return "schedule_review";
    }

    @Override
    public String label() {
        return "正在安排复习…";
    }

    @Override
    public boolean sideEffecting() {
        return true;
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.string("nodeCode", true, 32, ToolParamSpec.NODE_CODE_PATTERN),
                new ToolParamSpec(
                        "mode",
                        ToolParamSpec.ParamType.STRING,
                        true,
                        null,
                        null,
                        null,
                        MODES,
                        null));
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of(
                "nodeCode", Map.of(
                        "type", "string",
                        "description", "Knowledge node code to pin onto today's review queue"),
                "mode", Map.of(
                        "type", "string",
                        "enum", MODES,
                        "description", "ADVANCE = pull forward into today; TODAY = pin for today")));
        parameters.put("required", List.of("nodeCode", "mode"));
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description", "Pin a node onto today's review queue (idempotent same day).");
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode args) throws ToolException {
        String nodeCode = args == null ? null : args.path("nodeCode").asText(null);
        String modeText = args == null ? null : args.path("mode").asText(null);
        if (!StringUtils.hasText(modeText)) {
            throw new ToolException("mode is required");
        }
        ReviewScheduleStore.Mode mode;
        try {
            mode = ReviewScheduleStore.Mode.valueOf(modeText.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ToolException("mode must be ADVANCE or TODAY");
        }

        KgNode node = nodeToolSupport.requireNode(ctx.courseId(), nodeCode);
        boolean created = scheduleStore.pin(ctx.studentId(), ctx.courseId(), node.getNodeCode(), mode);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeCode", node.getNodeCode());
        body.put("name", node.getName());
        body.put("mode", mode.name());
        body.put("pinned", true);
        body.put("created", created);
        return toJson(body);
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize schedule_review result");
        }
    }
}
