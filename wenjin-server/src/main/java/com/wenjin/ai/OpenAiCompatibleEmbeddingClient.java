package com.wenjin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容 /embeddings 客户端（默认智谱 embedding-3）。
 * URL = {@code embedding.base-url + "/embeddings"}（base-url 已含版本路径，同视觉客户端）。
 * 配置每次调用现读（admin 热改即生效）。
 */
@Component
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiProperties properties;
    private final RestClient restClient;

    @Autowired
    public OpenAiCompatibleEmbeddingClient(AiProperties properties) {
        this(properties, RestClient.builder().requestFactory(ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofSeconds(10))
                        .withReadTimeout(Duration.ofSeconds(60)))));
    }

    /** 测试用：注入可绑定 MockRestServiceServer 的 builder。 */
    OpenAiCompatibleEmbeddingClient(AiProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    @Override
    public boolean available() {
        AiProperties.Embedding e = properties.getEmbedding();
        return e != null && e.isEnabled() && StringUtils.hasText(e.getApiKey());
    }

    @Override
    public String modelId() {
        AiProperties.Embedding e = properties.getEmbedding();
        return e.getModel() + "@" + e.getDimension();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        AiProperties.Embedding e = properties.getEmbedding();
        if (e == null || !e.isEnabled()) {
            throw new BusinessException(ResultCode.AI_ERROR, "未启用嵌入模型(wenjin.ai.embedding.enabled=false)");
        }
        if (!StringUtils.hasText(e.getApiKey())) {
            throw new BusinessException(ResultCode.AI_ERROR, "未配置嵌入模型 API Key(wenjin.ai.embedding.api-key)");
        }
        if (!StringUtils.hasText(e.getBaseUrl()) || !StringUtils.hasText(e.getModel())) {
            throw new BusinessException(ResultCode.AI_ERROR, "未配置嵌入模型基址或模型名");
        }
        int batch = Math.max(1, e.getBatchSize());
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batch) {
            List<String> part = texts.subList(from, Math.min(texts.size(), from + batch));
            out.addAll(callBatch(e, part));
        }
        return out;
    }

    private List<float[]> callBatch(AiProperties.Embedding e, List<String> part) {
        String url = trimTrailingSlash(e.getBaseUrl()) + "/embeddings";
        Map<String, Object> body = buildBody(e.getModel(), part, e.getDimension());
        JsonNode root;
        try {
            // 部分网关把 JSON 标成 application/octet-stream，按 byte[] 读再 Jackson 解析
            byte[] raw = restClient.post().uri(url)
                    .header("Authorization", "Bearer " + e.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(byte[].class);
            if (raw == null || raw.length == 0) {
                throw new BusinessException(ResultCode.AI_ERROR, "嵌入模型返回空响应");
            }
            root = MAPPER.readTree(raw);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.AI_ERROR, "嵌入模型调用失败：" + ex.getMessage());
        }
        return parseVectors(root, part.size(), e.getDimension());
    }

    static Map<String, Object> buildBody(String model, List<String> input, int dimension) {
        return Map.of("model", model, "input", input, "dimensions", dimension);
    }

    /** 按 data[].index 排序还原顺序；条数或维度不符 → AI_ERROR。 */
    static List<float[]> parseVectors(JsonNode root, int expectedCount, int dimension) {
        if (root == null || !root.path("data").isArray()) {
            throw new BusinessException(ResultCode.AI_ERROR, "嵌入模型返回结构异常(缺少 data)");
        }
        JsonNode data = root.get("data");
        if (data.size() != expectedCount) {
            throw new BusinessException(ResultCode.AI_ERROR,
                    "嵌入模型返回条数不符(期望 " + expectedCount + ",实际 " + data.size() + ")");
        }
        float[][] slots = new float[expectedCount][];
        Set<Integer> seen = new HashSet<>();
        int pos = 0;
        for (JsonNode item : data) {
            int idx = item.has("index") ? item.get("index").asInt(-1) : pos;
            pos++;
            if (idx < 0 || idx >= expectedCount || !seen.add(idx)) {
                throw new BusinessException(ResultCode.AI_ERROR, "嵌入模型返回 index 异常");
            }
            JsonNode emb = item.path("embedding");
            if (!emb.isArray() || emb.size() != dimension) {
                throw new BusinessException(ResultCode.AI_ERROR, "嵌入维度不符(期望 " + dimension + ",实际 "
                        + (emb.isArray() ? emb.size() : "无") + ")");
            }
            float[] v = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                v[i] = (float) emb.get(i).asDouble();
            }
            slots[idx] = v;
        }
        return List.of(slots);
    }

    private static String trimTrailingSlash(String base) {
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }
}
