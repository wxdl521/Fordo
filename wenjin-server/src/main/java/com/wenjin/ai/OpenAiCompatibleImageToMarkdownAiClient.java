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

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleImageToMarkdownAiClient implements ImageToMarkdownAiClient {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String INSTRUCTION =
            "你是文档版面识别助手。请将图片中的内容忠实转写为 Markdown：保留标题层级(#/##)、"
            + "表格(用 Markdown 表格)、有序/无序列表;只做转写,不要总结、不要增删内容。只输出 Markdown 正文。";

    private final AiProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleImageToMarkdownAiClient(AiProperties properties) {
        this.properties = properties;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(120));
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Override
    public String toMarkdown(byte[] jpegImageBytes) {
        AiProperties.Vision v = properties.getVision();
        if (v == null || !v.isEnabled()) {
            throw new BusinessException(ResultCode.AI_ERROR, "未配置视觉模型(wenjin.ai.vision.enabled=false)");
        }
        if (!StringUtils.hasText(v.getApiKey())) {
            throw new BusinessException(ResultCode.AI_ERROR, "未配置视觉模型 API Key(wenjin.ai.vision.api-key)");
        }
        String dataUri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpegImageBytes);
        Object body = buildBody(v.getModel(), dataUri);
        String url = trimTrailingSlash(v.getBaseUrl()) + "/chat/completions";
        JsonNode root;
        try {
            root = restClient.post().uri(url)
                    .header("Authorization", "Bearer " + v.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR, "视觉模型调用失败：" + e.getMessage());
        }
        return parseContent(root);
    }

    static Object buildBody(String model, String imageDataUri) {
        return Map.of(
                "model", model,
                "temperature", 0.1,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", INSTRUCTION),
                                Map.of("type", "image_url", "image_url", Map.of("url", imageDataUri))))));
    }

    static String parseContent(JsonNode root) {
        if (root == null || !root.has("choices") || !root.get("choices").isArray() || root.get("choices").isEmpty()) {
            throw new BusinessException(ResultCode.AI_ERROR, "视觉模型返回结构异常(缺少 choices)");
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new BusinessException(ResultCode.AI_ERROR, "视觉模型返回结构异常(缺少 message.content)");
        }
        return content.asText();
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
