package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.entity.KgNode;
import com.wenjin.service.PracticeService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class StartPracticeTool implements CompanionTool {

    private static final int DEFAULT_COUNT = 2;
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 10;
    private static final List<String> MODES = List.of("DIAGNOSTIC", "REVIEW", "DRILL");
    private static final String DEFAULT_MODE = "DRILL";

    private final PracticeService practiceService;
    private final ObjectMapper objectMapper;
    private final NodeToolSupport nodeToolSupport;

    public StartPracticeTool(PracticeService practiceService,
                             ObjectMapper objectMapper,
                             NodeToolSupport nodeToolSupport) {
        this.practiceService = Objects.requireNonNull(practiceService, "practiceService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.nodeToolSupport = Objects.requireNonNull(nodeToolSupport, "nodeToolSupport");
    }

    @Override
    public String name() {
        return "start_practice";
    }

    @Override
    public String label() {
        return "正在生成节点练习…";
    }

    @Override
    public boolean sideEffecting() {
        return true;
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.string("nodeCode", false, 32, ToolParamSpec.NODE_CODE_PATTERN),
                ToolParamSpec.string("nodeName", false, 128, null),
                ToolParamSpec.integer("count", false, (double) MIN_COUNT, (double) MAX_COUNT),
                // Legacy alias accepted for older prompts / tests.
                ToolParamSpec.integer("size", false, (double) MIN_COUNT, (double) MAX_COUNT),
                new ToolParamSpec(
                        "mode",
                        ToolParamSpec.ParamType.STRING,
                        false,
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
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("nodeCode", Map.of(
                "type", "string",
                "description", "Knowledge node code within the current course (preferred)"));
        properties.put("nodeName", Map.of(
                "type", "string",
                "description", "Exact knowledge node name when nodeCode is unknown"));
        properties.put("count", Map.of(
                "type", "integer",
                "description", "Practice question count (1-10, default 2). Clamped by practice service max."));
        properties.put("size", Map.of(
                "type", "integer",
                "description", "Legacy alias for count (1-10). Prefer count."));
        properties.put("mode", Map.of(
                "type", "string",
                "enum", MODES,
                "description", "Practice mode: DIAGNOSTIC / REVIEW / DRILL (default DRILL). Currently shares start pipeline."));
        parameters.put("properties", properties);
        parameters.put("anyOf", List.of(
                Map.of("required", List.of("nodeCode")),
                Map.of("required", List.of("nodeName"))));
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description",
                "When the student asks for practice, a quiz, or to drill a node, start a short graded practice session. Prefer nodeCode; mode default DRILL; count 1-10 default 2.");
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode args) throws ToolException {
        return startPractice(ctx, args).content();
    }

    @Override
    public ToolExecutionResult executeForGovernor(String toolCallId, ToolContext ctx, JsonNode args) throws ToolException {
        StartPracticeOutcome outcome = startPractice(ctx, args);
        return new ToolExecutionResult(toolCallId, name(), true, outcome.content(), outcome.practiceCard());
    }

    private StartPracticeOutcome startPractice(ToolContext ctx, JsonNode args) throws ToolException {
        String nodeCode = args == null ? null : args.path("nodeCode").asText(null);
        String nodeName = args == null ? null : args.path("nodeName").asText(null);
        if (!StringUtils.hasText(nodeCode) && !StringUtils.hasText(nodeName) && StringUtils.hasText(ctx.focusNodeCode())) {
            nodeCode = ctx.focusNodeCode();
        }
        KgNode node = nodeToolSupport.requireNode(ctx.courseId(), nodeCode, nodeName);
        int count = resolveCount(args);
        String mode = resolveMode(args);

        // Modes share the existing start pipeline for M5; mode is echoed for the model/UI.
        PracticeStartVO card = practiceService.start(
                ctx.studentId(),
                ctx.courseId(),
                node.getId(),
                null,
                count);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", card == null ? null : card.getSessionId());
        body.put("questionCount", card == null || card.getQuestions() == null ? 0 : card.getQuestions().size());
        body.put("mode", mode);
        body.put("nodeCode", node.getNodeCode());
        return new StartPracticeOutcome(toJson(body), card);
    }

    private int resolveCount(JsonNode args) {
        if (args == null) {
            return DEFAULT_COUNT;
        }
        JsonNode countNode = args.get("count");
        if (countNode == null || countNode.isNull()) {
            countNode = args.get("size");
        }
        if (countNode == null || countNode.isNull()) {
            return DEFAULT_COUNT;
        }
        int requested = countNode.asInt(DEFAULT_COUNT);
        return Math.max(MIN_COUNT, Math.min(MAX_COUNT, requested));
    }

    private String resolveMode(JsonNode args) {
        if (args == null || args.get("mode") == null || args.get("mode").isNull()) {
            return DEFAULT_MODE;
        }
        String text = args.get("mode").asText(DEFAULT_MODE);
        if (!StringUtils.hasText(text)) {
            return DEFAULT_MODE;
        }
        String upper = text.trim().toUpperCase();
        return MODES.contains(upper) ? upper : DEFAULT_MODE;
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize start_practice result");
        }
    }

    private record StartPracticeOutcome(String content, PracticeStartVO practiceCard) {
    }
}
