package com.wenjin.service.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.ProbeStartVO;
import com.wenjin.entity.KgNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Write tool: issue a one-shot understanding probe for a node (M9).
 *
 * <p>Params: {@code nodeCode} required. Prefers unused bank question; else EPHEMERAL.
 * Probe answers never write EWMA (see {@link ProbeService#submit}).
 */
@Component
public class ProbeUnderstandingTool implements CompanionTool {

    private final ProbeService probeService;
    private final ObjectMapper objectMapper;
    private final NodeToolSupport nodeToolSupport;
    private final FeatureFlagsProperties featureFlags;

    public ProbeUnderstandingTool(ProbeService probeService,
                                  ObjectMapper objectMapper,
                                  NodeToolSupport nodeToolSupport,
                                  FeatureFlagsProperties featureFlags) {
        this.probeService = Objects.requireNonNull(probeService, "probeService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.nodeToolSupport = Objects.requireNonNull(nodeToolSupport, "nodeToolSupport");
        this.featureFlags = featureFlags;
    }

    @Override
    public String name() {
        return "probe_understanding";
    }

    @Override
    public String label() {
        return "正在出理解探针…";
    }

    @Override
    public boolean sideEffecting() {
        return true;
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.string("nodeCode", true, 32, ToolParamSpec.NODE_CODE_PATTERN));
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("nodeCode", Map.of(
                "type", "string",
                "description", "Knowledge node code to probe within the current course"));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("nodeCode"));
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description",
                "Issue a short understanding probe (1 question) for a node after an explanation. "
                        + "Does not update mastery. Prefer unused bank items; else EPHEMERAL.");
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    @Override
    public ToolExecutionResult executeForGovernor(String toolCallId,
                                                  ToolContext ctx,
                                                  JsonNode args) throws ToolException {
        if (!reflectionEnabled()) {
            return notAllowed(toolCallId);
        }
        ProbeStartVO card = start(ctx, args);
        return new ToolExecutionResult(toolCallId, name(), true, toJson(summary(card)), null);
    }

    @Override
    public String execute(ToolContext ctx, JsonNode args) throws ToolException {
        if (!reflectionEnabled()) {
            throw new ToolException("反思探针功能未开启");
        }
        return toJson(summary(start(ctx, args)));
    }

    private ProbeStartVO start(ToolContext ctx, JsonNode args) throws ToolException {
        String nodeCode = args == null ? null : args.path("nodeCode").asText(null);
        if (!StringUtils.hasText(nodeCode) && StringUtils.hasText(ctx.focusNodeCode())) {
            nodeCode = ctx.focusNodeCode();
        }
        if (!StringUtils.hasText(nodeCode)) {
            throw new ToolException("nodeCode is required");
        }
        // Validate node belongs to course
        KgNode node = nodeToolSupport.requireNode(ctx.courseId(), nodeCode, null);
        try {
            return probeService.startProbe(ctx.studentId(), ctx.courseId(), node.getNodeCode());
        } catch (RuntimeException e) {
            throw new ToolException(e.getMessage() == null ? "probe failed" : e.getMessage());
        }
    }

    private Map<String, Object> summary(ProbeStartVO card) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", card.getSessionId());
        body.put("nodeCode", card.getNodeCode());
        body.put("source", card.getSource());
        body.put("ephemeral", card.isEphemeral());
        body.put("stem", card.getQuestion() == null ? null : card.getQuestion().getStem());
        return body;
    }

    private boolean reflectionEnabled() {
        return featureFlags == null || featureFlags.isReflectionEnabled();
    }

    private ToolExecutionResult notAllowed(String toolCallId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", ToolErrorCodes.TOOL_NOT_ALLOWED);
        body.put("message", "反思探针功能未开启");
        body.put("retryable", false);
        try {
            return new ToolExecutionResult(
                    toolCallId, name(), false, objectMapper.writeValueAsString(body), null, true, false);
        } catch (JsonProcessingException e) {
            return new ToolExecutionResult(
                    toolCallId, name(), false,
                    "{\"ok\":false,\"code\":\"TOOL_NOT_ALLOWED\",\"message\":\"反思探针功能未开启\",\"retryable\":false}",
                    null, true, false);
        }
    }

    private String toJson(Map<String, Object> body) throws ToolException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolException("Failed to serialize probe_understanding result");
        }
    }
}
