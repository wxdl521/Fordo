package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.MistakeReport;
import com.wenjin.service.MistakeQueryService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 错因辅导：读取学生近期客观题错题及所选干扰项指向的误区节点（只读、可缓存）。
 *
 * <p>{@code practiceSessionId} 缺省时取 {@link ToolContext#reviewPracticeSessionId()}（学生本轮点击
 * 「复盘错题」带来的、已由控制器校验归属的练习会话）。学生身份只来自上下文；即使模型传入他人会话 ID，
 * 查询仍限定本学生的作答，只会得到空结果。
 */
@Component
public class GetRecentMistakesTool implements CompanionTool {

    public static final String NAME = "get_recent_mistakes";
    static final int DEFAULT_LIMIT = 3;
    static final int MAX_LIMIT = 5;
    static final String EMPTY_NOTE = "近期没有错题";
    /**
     * 序列化结果上限：低于 governor 的工具结果截断阈值（默认 4000），保证结果始终是完整 JSON；
     * 超出时从最旧的错题开始丢弃。
     */
    static final int MAX_RESULT_CHARS = 3500;

    private final MistakeQueryService mistakeQueryService;
    private final ObjectMapper objectMapper;

    public GetRecentMistakesTool(MistakeQueryService mistakeQueryService, ObjectMapper objectMapper) {
        this.mistakeQueryService = Objects.requireNonNull(mistakeQueryService, "mistakeQueryService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String label() {
        return "正在翻看你的错题…";
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.string("nodeCode", false, 32, ToolParamSpec.NODE_CODE_PATTERN),
                ToolParamSpec.integer("practiceSessionId", false, 1.0, null),
                ToolParamSpec.integer("limit", false, 1.0, (double) MAX_LIMIT));
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("nodeCode", Map.of(
                "type", "string",
                "description", "Optional node code: keep mistakes whose main node or misconception node is this node"));
        properties.put("practiceSessionId", Map.of(
                "type", "integer",
                "description", "Optional practice session id to review; defaults to the session the student asked to review"));
        properties.put("limit", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", MAX_LIMIT,
                "description", "Max mistakes to return (1-5, default 3)"));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", NAME);
        function.put("description",
                "Get the student's recent wrong objective answers: chosen vs correct options and the misconception "
                        + "nodes their wrong choices point to, plus top misconceptions. Use before reviewing mistakes.");
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode args) throws ToolException {
        String nodeCode = textArg(args, "nodeCode");
        Long sessionId = args != null && args.hasNonNull("practiceSessionId")
                ? Long.valueOf(args.get("practiceSessionId").asLong())
                : ctx.reviewPracticeSessionId();
        int limit = args != null && args.hasNonNull("limit")
                ? Math.max(1, Math.min(MAX_LIMIT, args.get("limit").asInt(DEFAULT_LIMIT)))
                : DEFAULT_LIMIT;

        MistakeReport report = mistakeQueryService.report(ctx.studentId(), ctx.courseId(), nodeCode, sessionId, limit);
        if (report.mistakes().isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mistakes", List.of());
            body.put("note", EMPTY_NOTE);
            return toJson(body);
        }
        List<?> mistakes = report.mistakes();
        while (true) {
            // topMisconceptions 在前：即使下游再截断，误区节点也最先保留
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("topMisconceptions", report.topMisconceptions());
            body.put("mistakes", mistakes);
            String json = toJson(body);
            if (json.length() <= MAX_RESULT_CHARS || mistakes.size() <= 1) {
                return json;
            }
            mistakes = mistakes.subList(0, mistakes.size() - 1);
        }
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize get_recent_mistakes result");
        }
    }

    private static String textArg(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) {
            return null;
        }
        String value = args.get(field).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
