package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.entity.KgNode;
import com.wenjin.service.PracticeService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class StartPracticeTool implements CompanionTool {

    private static final int DEFAULT_SIZE = 2;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 3;

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
    public Map<String, Object> schema() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of(
                "nodeCode", Map.of(
                        "type", "string",
                        "description", "Knowledge node code within the current course"),
                "nodeName", Map.of(
                        "type", "string",
                        "description", "Exact knowledge node name within the current course when nodeCode is unknown"),
                "size", Map.of(
                        "type", "integer",
                        "description", "Practice question count hint. Defaults to 2 and is clamped to 1-3.")));
        parameters.put("anyOf", List.of(
                Map.of("required", List.of("nodeCode")),
                Map.of("required", List.of("nodeName"))));
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description", "Start a short practice session for one node.");
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
        KgNode node = nodeToolSupport.requireNode(ctx.courseId(), nodeCode, nodeName);
        int size = resolveSize(args);

        PracticeStartVO card = practiceService.start(
                ctx.studentId(),
                ctx.courseId(),
                node.getId(),
                null,
                size);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", card == null ? null : card.getSessionId());
        body.put("questionCount", card == null || card.getQuestions() == null ? 0 : card.getQuestions().size());
        return new StartPracticeOutcome(toJson(body), card);
    }

    private int resolveSize(JsonNode args) {
        if (args == null) {
            return DEFAULT_SIZE;
        }
        JsonNode sizeNode = args.get("size");
        if (sizeNode == null || sizeNode.isNull()) {
            return DEFAULT_SIZE;
        }
        int requested = sizeNode.asInt(DEFAULT_SIZE);
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, requested));
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
