package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.PlanVO;
import com.wenjin.service.plan.PlanService;
import com.wenjin.support.plan.PlanStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * get_plan_progress — 读档，缓存白名单（M10）。
 * <p>无计划返回明确空态，不编造。
 */
@Component
public class GetPlanProgressTool implements CompanionTool {

    private final PlanService planService;
    private final FeatureFlagsProperties featureFlags;
    private final ObjectMapper objectMapper;

    public GetPlanProgressTool(PlanService planService,
                               FeatureFlagsProperties featureFlags,
                               ObjectMapper objectMapper) {
        this.planService = Objects.requireNonNull(planService, "planService");
        this.featureFlags = featureFlags;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return "get_plan_progress";
    }

    @Override
    public String label() {
        return "正在查看计划进度…";
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
        function.put("description",
                "Get current study plan progress (today remaining tasks). Empty state when no plan.");
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode args) throws ToolException {
        if (!planEnabled()) {
            return emptyJson(false);
        }
        PlanVO plan = planService.progress(ctx.studentId(), ctx.courseId());
        Map<String, Object> body = new LinkedHashMap<>();
        if (plan == null || plan.getId() == null) {
            body.put("hasPlan", false);
            body.put("planId", null);
            body.put("status", null);
            body.put("todayRemaining", 0);
            body.put("taskCount", 0);
            body.put("doneCount", 0);
            body.put("message", "当前没有学习计划");
            return toJson(body);
        }
        int todayRemaining = 0;
        if (plan.getTodayTasks() != null) {
            todayRemaining = (int) plan.getTodayTasks().stream()
                    .filter(t -> t.getStatus() == null || t.getStatus() == 0)
                    .count();
        }
        body.put("hasPlan", true);
        body.put("planId", plan.getId());
        body.put("status", statusName(plan.getStatus()));
        body.put("todayRemaining", todayRemaining);
        body.put("taskCount", plan.getTaskCount());
        body.put("doneCount", plan.getDoneCount());
        body.put("targetDate", plan.getTargetDate() == null ? null : plan.getTargetDate().toString());
        body.put("atRisk", plan.isAtRisk());
        return toJson(body);
    }

    @Override
    public ToolExecutionResult executeForGovernor(String toolCallId,
                                                  ToolContext ctx,
                                                  JsonNode args) throws ToolException {
        if (!planEnabled()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("code", ToolErrorCodes.TOOL_NOT_ALLOWED);
            body.put("message", "学习计划功能未开启");
            try {
                return new ToolExecutionResult(
                        toolCallId, name(), false, objectMapper.writeValueAsString(body), null, true, false);
            } catch (JsonProcessingException e) {
                return new ToolExecutionResult(toolCallId, name(), false, "{}", null, true, false);
            }
        }
        return CompanionTool.super.executeForGovernor(toolCallId, ctx, args);
    }

    private static String statusName(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case PlanStatus.DRAFT -> "DRAFT";
            case PlanStatus.ACTIVE -> "ACTIVE";
            case PlanStatus.DONE -> "DONE";
            case PlanStatus.CANCELLED -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }

    private boolean planEnabled() {
        return featureFlags == null || featureFlags.isPlanEnabled();
    }

    private String emptyJson(boolean hasPlan) throws ToolException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hasPlan", hasPlan);
        body.put("message", "当前没有学习计划");
        return toJson(body);
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize get_plan_progress result");
        }
    }
}
