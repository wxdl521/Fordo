package com.wenjin.eval.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.config.AiProperties;
import com.wenjin.eval.metrics.EvalProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Judge client over an OpenAI-compatible {@code /v1/chat/completions} endpoint.
 * Uses {@code response_format=json_object} and temperature 0. Model = {@code wenjin.eval.judge-model},
 * falling back to {@code wenjin.ai.model}. Request building / response parsing are static for offline tests.
 */
public class OpenAiCompatibleJudgeAiClient implements JudgeAiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiProperties ai;
    private final EvalProperties eval;
    private final RestClient restClient;

    public OpenAiCompatibleJudgeAiClient(AiProperties ai, EvalProperties eval) {
        this.ai = ai;
        this.eval = eval;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(120));
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Override
    public String complete(String prompt) {
        if (!ai.isEnabled()) {
            throw new IllegalStateException("AI disabled (wenjin.ai.enabled=false)");
        }
        if (!StringUtils.hasText(ai.getApiKey())) {
            throw new IllegalStateException("judge: wenjin.ai.api-key not configured");
        }
        Map<String, Object> body = buildRequestBody(resolveModel(eval.getJudgeModel(), ai.getModel()), prompt);
        String response = restClient.post()
                .uri(chatCompletionsUrl(ai.getBaseUrl()))
                .header("Authorization", "Bearer " + ai.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return parseContent(response);
    }

    public static String resolveModel(String judgeModel, String aiModel) {
        return StringUtils.hasText(judgeModel) ? judgeModel.trim() : aiModel;
    }

    public static String chatCompletionsUrl(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + "/v1/chat/completions";
    }

    public static Map<String, Object> buildRequestBody(String model, String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt == null ? "" : prompt)));
        body.put("temperature", 0);
        body.put("stream", false);
        body.put("response_format", Map.of("type", "json_object"));
        return body;
    }

    /** Extract {@code choices[0].message.content}; throws if absent. */
    public static String parseContent(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("judge: empty HTTP response");
        }
        try {
            JsonNode content = MAPPER.readTree(responseBody).path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new IllegalStateException("judge: response has no choices[0].message.content");
            }
            return content.asText();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("judge: unparseable response: " + e.getMessage(), e);
        }
    }
}
