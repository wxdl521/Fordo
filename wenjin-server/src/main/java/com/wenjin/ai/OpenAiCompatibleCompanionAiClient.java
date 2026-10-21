package com.wenjin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AiProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容的 AI 学习伴侣流式客户端（默认 DeepSeek）。
 * <p>
 * 使用 RestClient.exchange() 获取原始响应流，逐行解析 SSE。
 * SSE 解析做成静态方法 parseDeltaContent，便于无网络单元测试。
 */
@Component
public class OpenAiCompatibleCompanionAiClient implements CompanionAiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleCompanionAiClient(AiProperties properties) {
        this.properties = properties;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Override
    public void stream(String systemPrompt, List<ChatMsg> history, String userMessage, Consumer<String> onToken) {
        ensureEnabledAndKey();

        String url = chatCompletionsUrl();

        // 构建 messages 数组：system + history + user
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) {
            for (ChatMsg msg : history) {
                messages.add(Map.of("role", msg.role(), "content", msg.content()));
            }
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", messages,
                "temperature", properties.getTemperature() == null ? 0.7 : properties.getTemperature(),
                "stream", true);

        try {
            restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        // 检查 HTTP 状态码
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            String errorBody = "";
                            try (InputStream is = response.getBody();
                                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                                errorBody = reader.lines().reduce("", (a, b) -> a + b);
                            } catch (Exception readErr) {
                                // 吞掉读错误体的异常，保留原状态码
                            }
                            throw new BusinessException(ResultCode.AI_ERROR,
                                    "AI服务返回错误：HTTP " + response.getStatusCode().value() + " " + errorBody);
                        }

                        // 读取原始 SSE 流，确保异常时资源清理
                        try (InputStream is = response.getBody();
                             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                String token = parseDeltaContent(line);
                                if (token != null) {
                                    onToken.accept(token);
                                }
                            }
                        } catch (Exception streamErr) {
                            // 确保流异常时资源已清理（try-with-resources 保证）
                            throw new BusinessException(ResultCode.AI_ERROR, "AI服务流处理失败：" + streamErr.getMessage());
                        }
                        return null;
                    });
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：" + e.getMessage());
        }
    }

    /**
     * 解析 SSE data 行，提取 choices[0].delta.content。
     * <p>
     * 返回值语义：
     * <ul>
     *   <li>非 data: 行、[DONE]、空 data、缺 content 字段、content 为 null → 返回 null</li>
     *   <li>content 为空串 "" (常见于首块 role chunk) → 返回空串 ""</li>
     *   <li>其他 → 返回 content 的字符串值</li>
     * </ul>
     *
     * @param line SSE 原始行
     * @return 增量 content，或 null（跳过该行）
     */
    static String parseDeltaContent(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String trimmed = line.trim();
        // 非 data: 开头的行（注释、event 等）
        if (!trimmed.startsWith("data:")) {
            return null;
        }
        String data = trimmed.substring(5).trim();
        // [DONE] 结束标记
        if (data.equals("[DONE]")) {
            return null;
        }
        // 空 data
        if (data.isEmpty()) {
            return null;
        }

        try {
            JsonNode root = MAPPER.readTree(data);
            JsonNode choices = root.path("choices");
            if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
                return null;
            }
            JsonNode delta = choices.get(0).path("delta");
            if (delta.isMissingNode()) {
                return null;
            }
            JsonNode contentNode = delta.path("content");
            // content 字段不存在或为 null
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                return null;
            }
            // content 存在，返回其字符串值（可能是空串 ""）
            return contentNode.asText();
        } catch (Exception e) {
            // JSON 解析失败，记录调试信息后跳过该行
            org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleCompanionAiClient.class)
                    .debug("SSE 行解析失败（已跳过）: {}", data, e);
            return null;
        }
    }

    @Override
    public TurnOutcome completeWithTools(List<Map<String, Object>> messages, List<Map<String, Object>> toolSchemas) {
        ensureEnabledAndKey();

        Map<String, Object> body = buildToolRequestBody(
                properties.getModel(),
                temperature(),
                messages,
                toolSchemas);

        try {
            String responseBody = restClient.post()
                    .uri(chatCompletionsUrl())
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new BusinessException(ResultCode.AI_ERROR,
                                    "AI服务返回错误：HTTP " + response.getStatusCode().value() + " "
                                            + readBody(response.getBody()));
                        }
                        return readBody(response.getBody());
                    });
            return parseTurnOutcome(responseBody);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：" + e.getMessage());
        }
    }

    @Override
    public void streamMessages(List<Map<String, Object>> messages, Consumer<String> onToken) {
        ensureEnabledAndKey();

        Map<String, Object> body = buildStreamRequestBody(
                properties.getModel(),
                temperature(),
                messages);

        try {
            restClient.post()
                    .uri(chatCompletionsUrl())
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new BusinessException(ResultCode.AI_ERROR,
                                    "AI服务返回错误：HTTP " + response.getStatusCode().value() + " "
                                            + readBody(response.getBody()));
                        }

                        try (InputStream is = response.getBody();
                             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                String token = parseDeltaContent(line);
                                if (token != null) {
                                    onToken.accept(token);
                                }
                            }
                        } catch (Exception streamErr) {
                            throw new BusinessException(ResultCode.AI_ERROR, "AI服务流处理失败：" + streamErr.getMessage());
                        }
                        return null;
                    });
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：" + e.getMessage());
        }
    }

    @Override
    public StreamTurnResult streamTurn(List<Map<String, Object>> messages,
                                       List<Map<String, Object>> toolSchemas,
                                       ToolChoice toolChoice,
                                       Consumer<String> onContentDelta) {
        ensureEnabledAndKey();

        Map<String, Object> body = buildStreamTurnRequestBody(
                properties.getModel(), temperature(), messages, toolSchemas, toolChoice);
        StreamTurnAccumulator acc = new StreamTurnAccumulator(onContentDelta);

        try {
            restClient.post()
                    .uri(chatCompletionsUrl())
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new BusinessException(ResultCode.AI_ERROR,
                                    "AI服务返回错误：HTTP " + response.getStatusCode().value() + " "
                                            + readBody(response.getBody()));
                        }
                        try (InputStream is = response.getBody();
                             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                acc.accept(line);
                            }
                        } catch (SinkFailureException sfe) {
                            throw sfe;
                        } catch (Exception streamErr) {
                            throw new BusinessException(ResultCode.AI_ERROR, "AI服务流处理失败：" + streamErr.getMessage());
                        }
                        return null;
                    });
        } catch (SinkFailureException sfe) {
            throw sfe;
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：" + e.getMessage());
        }
        return acc.result();
    }

    static Map<String, Object> buildStreamTurnRequestBody(
            String model,
            Double temperature,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> toolSchemas,
            ToolChoice toolChoice) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages == null ? List.of() : messages);
        body.put("temperature", temperature);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            body.put("tools", toolSchemas);
            body.put("tool_choice", toolChoice == ToolChoice.NONE ? "none" : "auto");
        }
        return body;
    }

    static TurnOutcome parseTurnOutcome(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode choices = root.path("choices");
            if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
                return new FinalText("");
            }
            JsonNode message = choices.get(0).path("message");
            if (message.isMissingNode() || message.isNull()) {
                return new FinalText("");
            }

            JsonNode toolCallsNode = message.path("tool_calls");
            if (toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                List<ToolCall> calls = new ArrayList<>();
                for (JsonNode callNode : toolCallsNode) {
                    JsonNode functionNode = callNode.path("function");
                    String name = functionNode.path("name").asText("");
                    if (!StringUtils.hasText(name)) {
                        continue;
                    }
                    JsonNode argumentsNode = functionNode.path("arguments");
                    String argumentsJson;
                    if (argumentsNode.isMissingNode() || argumentsNode.isNull()) {
                        argumentsJson = "{}";
                    } else if (argumentsNode.isTextual()) {
                        argumentsJson = argumentsNode.asText();
                    } else {
                        argumentsJson = argumentsNode.toString();
                    }
                    calls.add(new ToolCall(callNode.path("id").asText(""), name, argumentsJson));
                }
                if (!calls.isEmpty()) {
                    return new ToolCalls(calls);
                }
            }

            JsonNode contentNode = message.path("content");
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                return new FinalText("");
            }
            return new FinalText(contentNode.asText());
        } catch (Exception e) {
            return new FinalText("");
        }
    }

    static Map<String, Object> buildToolRequestBody(
            String model,
            Double temperature,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> toolSchemas) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages == null ? List.of() : messages);
        body.put("temperature", temperature);
        body.put("stream", false);
        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            body.put("tools", toolSchemas);
            body.put("tool_choice", "auto");
        }
        return body;
    }

    static Map<String, Object> buildStreamRequestBody(
            String model,
            Double temperature,
            List<Map<String, Object>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages == null ? List.of() : messages);
        body.put("temperature", temperature);
        body.put("stream", true);
        return body;
    }

    private void ensureEnabledAndKey() {
        if (!properties.isEnabled()) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI 功能已禁用（wenjin.ai.enabled=false）");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：未配置 API Key（wenjin.ai.api-key）");
        }
    }

    private String chatCompletionsUrl() {
        return trimTrailingSlash(properties.getBaseUrl()) + "/v1/chat/completions";
    }

    private Double temperature() {
        return properties.getTemperature() == null ? 0.7 : properties.getTemperature();
    }

    private static String readBody(InputStream body) {
        if (body == null) {
            return "";
        }
        try (InputStream is = body;
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().reduce("", (a, b) -> a + b);
        } catch (Exception e) {
            return "";
        }
    }

    private static String trimTrailingSlash(String base) {
        if (base == null) {
            return "";
        }
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }
}
