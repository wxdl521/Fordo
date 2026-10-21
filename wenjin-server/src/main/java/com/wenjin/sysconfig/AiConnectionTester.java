package com.wenjin.sysconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenjin.config.AiProperties;
import com.wenjin.dto.AdminAiTestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * AI「测试连接」：用<b>当前生效</b>的配置（读 {@link AiProperties}，含刚保存的覆盖值）发一个极小的
 * chat/completions 请求（max_tokens 很小），返回成功与否、耗时与脱敏后的错误摘要。
 *
 * <p>URL 拼法与业务客户端一致：文本 {@code base-url + /v1/chat/completions}（同 OpenAiCompatibleQuestionAiClient），
 * 视觉 {@code vision.base-url + /chat/completions}（同 OpenAiCompatibleImageToMarkdownAiClient）。
 * 超时：连接 5s、读取 12s，管理员点一下最多等十几秒。</p>
 *
 * <p>错误摘要绝不含 API Key：先抹掉当前 key 原文，再抹掉形如 {@code sk-xxxx} / {@code Bearer xxx} 的片段，最后截断。</p>
 */
@Component
public class AiConnectionTester {

    public static final String TARGET_TEXT = "text";
    public static final String TARGET_VISION = "vision";

    static final int MAX_TOKENS = 5;
    static final int MAX_ERROR_CHARS = 300;
    private static final String PROBE_PROMPT = "ping";
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+\\S+");
    private static final Pattern SK_LIKE = Pattern.compile("\\bsk-[A-Za-z0-9*._\\-]{4,}");

    private final AiProperties properties;
    private final RestClient restClient;

    @Autowired
    public AiConnectionTester(AiProperties properties) {
        this(properties, RestClient.builder().requestFactory(ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofSeconds(5))
                        .withReadTimeout(Duration.ofSeconds(12)))));
    }

    /** 测试用：注入可绑定 MockRestServiceServer 的 builder。 */
    AiConnectionTester(AiProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    /**
     * @param target {@code text} 或 {@code vision}（控制器已校验）
     */
    public AdminAiTestVO test(String target) {
        boolean vision = TARGET_VISION.equals(target);
        boolean enabled;
        String baseUrl;
        String apiKey;
        String model;
        if (vision) {
            AiProperties.Vision v = properties.getVision();
            enabled = v != null && v.isEnabled();
            baseUrl = v == null ? null : v.getBaseUrl();
            apiKey = v == null ? null : v.getApiKey();
            model = v == null ? null : v.getModel();
        } else {
            enabled = properties.isEnabled();
            baseUrl = properties.getBaseUrl();
            apiKey = properties.getApiKey();
            model = properties.getModel();
        }

        if (!enabled) {
            return new AdminAiTestVO(false, 0, model, vision ? "视觉模型已关闭（wenjin.ai.vision.enabled=false）"
                    : "文本模型已关闭（wenjin.ai.enabled=false）");
        }
        if (!StringUtils.hasText(apiKey)) {
            return new AdminAiTestVO(false, 0, model, "未配置 API Key");
        }
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(model)) {
            return new AdminAiTestVO(false, 0, model, "未配置服务基址或模型名");
        }

        String url = trimTrailingSlash(baseUrl) + (vision ? "/chat/completions" : "/v1/chat/completions");
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", PROBE_PROMPT)),
                "max_tokens", MAX_TOKENS,
                "temperature", 0,
                "stream", false);

        long started = System.nanoTime();
        try {
            JsonNode root = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            long latency = elapsedMs(started);
            if (root == null || !root.path("choices").isArray()) {
                return new AdminAiTestVO(false, latency, model, "返回结构异常（缺少 choices）");
            }
            return new AdminAiTestVO(true, latency, model, null);
        } catch (RestClientResponseException e) {
            String detail = "HTTP " + e.getStatusCode().value() + " " + e.getResponseBodyAsString();
            return new AdminAiTestVO(false, elapsedMs(started), model, sanitize(detail, apiKey));
        } catch (ResourceAccessException e) {
            Throwable cause = e.getMostSpecificCause();
            String detail = "连接失败：" + cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : " " + cause.getMessage());
            return new AdminAiTestVO(false, elapsedMs(started), model, sanitize(detail, apiKey));
        } catch (Exception e) {
            String detail = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
            return new AdminAiTestVO(false, elapsedMs(started), model, sanitize(detail, apiKey));
        }
    }

    /** 抹掉 key 原文与形似密钥的片段，压成单行并截断。 */
    static String sanitize(String text, String apiKey) {
        if (text == null) {
            return null;
        }
        String s = text;
        if (StringUtils.hasText(apiKey)) {
            s = s.replace(apiKey.trim(), "****");
        }
        s = BEARER.matcher(s).replaceAll("Bearer ****");
        s = SK_LIKE.matcher(s).replaceAll("sk-****");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > MAX_ERROR_CHARS) {
            s = s.substring(0, MAX_ERROR_CHARS) + "…";
        }
        return s;
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static String trimTrailingSlash(String base) {
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }
}
