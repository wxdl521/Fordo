package com.wenjin.service.teacher.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.InsightCard;
import com.wenjin.service.companion.CompanionTool;
import com.wenjin.service.companion.ToolAudience;
import com.wenjin.service.companion.ToolContext;
import com.wenjin.service.companion.ToolErrorCodes;
import com.wenjin.service.companion.ToolException;
import com.wenjin.service.companion.ToolExecutionResult;
import com.wenjin.service.teacher.TeacherAnalyticsService;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 教师助教只读工具基类：受众 TEACHER、无副作用（均为只读，可按 run 内缓存）、无 courseId 参数（只用上下文课程，
 * 该课程已由请求线程完成授权），防御性校验 {@code ctx.isTeacher()}。
 */
abstract class AbstractTeacherTool implements CompanionTool {

    /** 名单卡片的单元格截断长度。 */
    static final int CELL_MAX = 40;

    protected final TeacherAnalyticsService service;
    protected final ObjectMapper objectMapper;

    protected AbstractTeacherTool(TeacherAnalyticsService service, ObjectMapper objectMapper) {
        this.service = Objects.requireNonNull(service, "service");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** 模型可见的工具说明。 */
    protected abstract String description();

    /** JSON-schema properties（无 courseId）。 */
    protected abstract Map<String, Object> properties();

    /** 业务体：返回结果对象（序列化为 JSON）与可选卡片。 */
    protected abstract Output run(ToolContext ctx, JsonNode args) throws ToolException;

    protected record Output(Object body, InsightCard card) {
    }

    @Override
    public ToolAudience audience() {
        return ToolAudience.TEACHER;
    }

    @Override
    public boolean sideEffecting() {
        return false;
    }

    /** 只读学情查询：同一 run 内可缓存，同一轮多个调用可并行。 */
    @Override
    public boolean readCacheable() {
        return true;
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties());
        parameters.put("additionalProperties", false);
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description", description());
        function.put("parameters", parameters);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    @Override
    public ToolExecutionResult executeForGovernor(String toolCallId, ToolContext ctx, JsonNode args)
            throws ToolException {
        if (ctx == null || !ctx.isTeacher() || ctx.courseId() == null) {
            return notAllowed(toolCallId);
        }
        Output out = run(ctx, args == null ? objectMapper.createObjectNode() : args);
        String json;
        try {
            json = objectMapper.writeValueAsString(out.body());
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize " + name() + " result");
        }
        return new ToolExecutionResult(toolCallId, name(), true, json, null, false, false, null, out.card());
    }

    @Override
    public String execute(ToolContext ctx, JsonNode args) throws ToolException {
        ToolExecutionResult result = executeForGovernor("", ctx, args);
        if (!result.ok()) {
            throw new ToolException("该工具仅限教师助教会话使用");
        }
        return result.content();
    }

    private ToolExecutionResult notAllowed(String toolCallId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", ToolErrorCodes.TOOL_NOT_ALLOWED);
        body.put("message", "该工具仅限教师助教会话使用");
        body.put("retryable", false);
        String content;
        try {
            content = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            content = "{\"ok\":false,\"code\":\"TOOL_NOT_ALLOWED\",\"retryable\":false}";
        }
        return new ToolExecutionResult(toolCallId, name(), false, content, null, true, false);
    }

    // ── helpers ──

    protected static String cell(Object v) {
        if (v == null) {
            return "—";
        }
        String s = String.valueOf(v).replaceAll("\\s+", " ").trim();
        return s.length() <= CELL_MAX ? s : s.substring(0, CELL_MAX) + "…";
    }

    protected static String num(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%.1f", v);
    }

    protected static int intArg(JsonNode args, String field, int dflt) {
        return args != null && args.hasNonNull(field) && args.get(field).canConvertToInt()
                ? args.get(field).asInt() : dflt;
    }

    protected static String strArg(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) {
            return null;
        }
        String s = args.get(field).asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    protected static Map<String, Object> prop(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", description);
        return m;
    }
}
