package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.DraftPlanResultVO;
import com.wenjin.service.plan.PlanService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * draft_study_plan — 危档，只产 DRAFT（M10）。
 * <p>params：targetDate DATE 必填（&gt;今天+1），scopeNodes ARRAY≤42 可空。
 */
@Component
public class DraftStudyPlanTool implements CompanionTool {

    private final PlanService planService;
    private final FeatureFlagsProperties featureFlags;
    private final LimitsProperties limits;
    private final ObjectMapper objectMapper;

    public DraftStudyPlanTool(PlanService planService,
                              FeatureFlagsProperties featureFlags,
                              LimitsProperties limits,
                              ObjectMapper objectMapper) {
        this.planService = Objects.requireNonNull(planService, "planService");
        this.featureFlags = featureFlags;
        this.limits = limits == null ? new LimitsProperties() : limits;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return "draft_study_plan";
    }

    @Override
    public String label() {
        return "正在生成学习计划草稿…";
    }

    @Override
    public boolean sideEffecting() {
        return true;
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.string("targetDate", true, 32, null),
                ToolParamSpec.array("scopeNodes", false));
    }

    @Override
    public Map<String, Object> schema() {
        int maxScope = limits.getPlan().getMaxScopeNodes();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("targetDate", Map.of(
                "type", "string",
                "description", "ISO date (YYYY-MM-DD), must be after today+1"));
        properties.put("scopeNodes", Map.of(
                "type", "array",
                "description", "Optional node scope (max " + maxScope + ")",
                "maxItems", maxScope,
                "items", Map.of("type", "string")));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("targetDate"));
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description",
                "Draft an exam-prep study plan (DRAFT only; student must confirm). Deterministic tasks, no LLM packing.");
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode args) throws ToolException {
        if (!planEnabled()) {
            throw new ToolException("学习计划功能未开启");
        }
        String dateText = args == null ? null : args.path("targetDate").asText(null);
        if (!StringUtils.hasText(dateText)) {
            throw new ToolException("targetDate is required");
        }
        LocalDate targetDate;
        try {
            targetDate = LocalDate.parse(dateText.trim());
        } catch (DateTimeParseException e) {
            throw new ToolException("targetDate must be ISO date YYYY-MM-DD");
        }
        List<String> scope = parseScope(args == null ? null : args.get("scopeNodes"));

        DraftPlanResultVO result = planService.draft(
                ctx.studentId(), ctx.courseId(), targetDate, scope, null);

        Map<String, Object> body = new LinkedHashMap<>();
        if (result.getErrorCode() != null) {
            body.put("ok", false);
            body.put("errorCode", result.getErrorCode());
            body.put("suggestedScope", result.getSuggestedScope() == null
                    ? List.of() : result.getSuggestedScope());
            body.put("planId", null);
            body.put("days", 0);
            body.put("taskCount", 0);
            body.put("explain", null);
        } else {
            body.put("ok", true);
            body.put("planId", result.getPlanId());
            body.put("days", result.getDays());
            body.put("taskCount", result.getTaskCount());
            body.put("explain", result.getExplain());
        }
        return toJson(body);
    }

    @Override
    public ToolExecutionResult executeForGovernor(String toolCallId,
                                                  ToolContext ctx,
                                                  JsonNode args) throws ToolException {
        if (!planEnabled()) {
            return notAllowed(toolCallId);
        }
        return CompanionTool.super.executeForGovernor(toolCallId, ctx, args);
    }

    private List<String> parseScope(JsonNode node) throws ToolException {
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        int max = limits.getPlan().getMaxScopeNodes();
        if (node.size() > max) {
            throw new ToolException("scopeNodes exceeds max size " + max);
        }
        List<String> codes = new ArrayList<>();
        for (JsonNode el : node) {
            if (el != null && el.isTextual() && StringUtils.hasText(el.asText())) {
                codes.add(el.asText().trim());
            }
        }
        return codes;
    }

    private boolean planEnabled() {
        return featureFlags == null || featureFlags.isPlanEnabled();
    }

    private ToolExecutionResult notAllowed(String toolCallId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", ToolErrorCodes.TOOL_NOT_ALLOWED);
        body.put("message", "学习计划功能未开启");
        body.put("retryable", false);
        try {
            return new ToolExecutionResult(
                    toolCallId, name(), false, objectMapper.writeValueAsString(body), null, true, false);
        } catch (JsonProcessingException e) {
            return new ToolExecutionResult(
                    toolCallId, name(), false,
                    "{\"ok\":false,\"code\":\"TOOL_NOT_ALLOWED\"}", null, true, false);
        }
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize draft_study_plan result");
        }
    }
}
