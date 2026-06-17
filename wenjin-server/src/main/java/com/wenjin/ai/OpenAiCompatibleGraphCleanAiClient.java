package com.wenjin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AiProperties;
import com.wenjin.dto.GraphImportRequest;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的图谱 AI 清洗客户端（默认 DeepSeek）。
 * <p>
 * 将 Excel 解析出的原始节点/边发送给 AI，修正格式、补充缺失字段、标准化关系类型。
 * prompt 构造与 JSON 解析做成包可见静态方法，便于无网络单元测试。
 */
@Component
public class OpenAiCompatibleGraphCleanAiClient implements GraphCleanAiClient {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleGraphCleanAiClient(AiProperties properties) {
        this.properties = properties;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Override
    public GraphImportRequest clean(List<GraphImportRequest.NodeItem> rawNodes,
                                    List<GraphImportRequest.EdgeItem> rawEdges) {
        String prompt = buildCleanPrompt(rawNodes, rawEdges);
        String content = call(prompt);
        try {
            return parseCleanResult(content);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR,
                    "AI服务调用失败：图谱清洗结果解析失败 " + e.getMessage());
        }
    }

    // ============================ 网络调用 ============================

    /**
     * 调用 OpenAI 兼容的 chat/completions（非流式），取出 choices[0].message.content。
     */
    String call(String prompt) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI 功能已禁用（wenjin.ai.enabled=false）");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：未配置 API Key（wenjin.ai.api-key）");
        }
        String url = trimTrailingSlash(properties.getBaseUrl()) + "/v1/chat/completions";
        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", properties.getTemperature() == null ? 0.2 : properties.getTemperature(),
                "response_format", Map.of("type", "json_object"));

        JsonNode root;
        try {
            root = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：" + e.getMessage());
        }

        if (root == null || !root.has("choices") || !root.get("choices").isArray()
                || root.get("choices").isEmpty()) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：返回结构异常（缺少 choices）");
        }
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：返回结构异常（缺少 message.content）");
        }
        return contentNode.asText();
    }

    // ============================ Prompt 构造（包可见，可测） ============================

    static String buildCleanPrompt(List<GraphImportRequest.NodeItem> rawNodes,
                                   List<GraphImportRequest.EdgeItem> rawEdges) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是软件工程知识图谱的数据清洗专家。请清洗以下从 Excel 解析出的图谱数据。\n\n");
        sb.append("清洗规则：\n");
        sb.append("1. 关系类型标准化：将中文关系映射为以下四种之一——\"前置\"、\"包含\"、\"相关\"、\"应用\"。\n");
        sb.append("   - \"依赖\"→\"前置\"，\"属于\"→\"包含\"，\"关联\"→\"相关\"，\"对应\"→\"应用\" 等常见同义词映射。\n");
        sb.append("   - 无法映射的保留原值。\n");
        sb.append("2. difficulty 标准化：确保为 1-5 的整数，不在范围内或缺失的修正为 3。\n");
        sb.append("3. bloom 层级补充：若缺失，根据知识点名称推断为以下之一——\"记忆\"、\"理解\"、\"应用\"、\"分析\"、\"评价\"、\"创造\"。\n");
        sb.append("4. is_key 标准化：将 \"是\"/\"true\"/\"1\"/\"Y\" 映射为 true，其他映射为 false。\n");
        sb.append("5. 边端点校验：若 source 或 target 不在节点 id 列表中，在 note 字段标注 \"[警告]端点不存在\"，但保留该边。\n");
        sb.append("6. 空 id 的节点补充为 \"AUTO_\" + 序号。\n");
        sb.append("7. 空 name 的节点保留，但 note 标注 \"[警告]缺少名称\"。\n\n");

        sb.append("以下是原始数据（JSON 格式）：\n");
        sb.append("节点列表：\n");
        try {
            sb.append(MAPPER.writeValueAsString(rawNodes));
        } catch (Exception e) {
            sb.append("[]");
        }
        sb.append("\n边列表：\n");
        try {
            sb.append(MAPPER.writeValueAsString(rawEdges));
        } catch (Exception e) {
            sb.append("[]");
        }

        sb.append("\n\n只返回一个 JSON 对象（不要任何多余文字、不要 Markdown 围栏），结构如下：\n");
        sb.append("{\"nodes\":[{\"id\":\"...\",\"name\":\"...\",\"chapter\":\"...\",\"difficulty\":3,");
        sb.append("\"is_key\":false,\"bloom\":\"...\",\"description\":\"...\",\"note\":\"...\"}],\n");
        sb.append("\"edges\":[{\"source\":\"...\",\"target\":\"...\",\"type\":\"前置\",\"note\":\"...\"}]}\n");
        return sb.toString();
    }

    // ============================ JSON 解析（包可见，可测） ============================

    /**
     * 解析 AI 清洗结果 JSON 为 GraphImportRequest。
     */
    static GraphImportRequest parseCleanResult(String content) {
        String json = stripJsonFence(content);
        try {
            JsonNode root = MAPPER.readTree(json);

            GraphImportRequest request = new GraphImportRequest();

            // 解析节点
            JsonNode nodesNode = root.path("nodes");
            List<GraphImportRequest.NodeItem> nodes = new ArrayList<>();
            if (nodesNode.isArray()) {
                for (JsonNode n : nodesNode) {
                    GraphImportRequest.NodeItem item = new GraphImportRequest.NodeItem();
                    item.setId(n.path("id").asText(null));
                    item.setName(n.path("name").asText(null));
                    item.setChapter(n.path("chapter").asText(null));
                    JsonNode diffNode = n.path("difficulty");
                    if (!diffNode.isMissingNode() && !diffNode.isNull()) {
                        item.setDifficulty(diffNode.asInt(3));
                    }
                    item.setIsKey(n.path("is_key").asBoolean(false));
                    item.setBloom(n.path("bloom").asText(null));
                    item.setDescription(n.path("description").asText(null));
                    item.setNote(n.path("note").asText(null));
                    nodes.add(item);
                }
            }
            request.setNodes(nodes);

            // 解析边
            JsonNode edgesNode = root.path("edges");
            List<GraphImportRequest.EdgeItem> edges = new ArrayList<>();
            if (edgesNode.isArray()) {
                for (JsonNode e : edgesNode) {
                    GraphImportRequest.EdgeItem item = new GraphImportRequest.EdgeItem();
                    item.setSource(e.path("source").asText(null));
                    item.setTarget(e.path("target").asText(null));
                    item.setType(e.path("type").asText(null));
                    item.setNote(e.path("note").asText(null));
                    edges.add(item);
                }
            }
            request.setEdges(edges);

            return request;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR,
                    "AI服务调用失败：图谱清洗结果解析失败 " + e.getMessage());
        }
    }

    // ============================ 工具方法 ============================

    /**
     * 剥离 Markdown 代码围栏，截取首个 JSON 对象/数组。
     */
    static String stripJsonFence(String content) {
        if (content == null) {
            return "";
        }
        String s = content.trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int start = s.indexOf('\n', fence);
            int end = s.indexOf("```", fence + 3);
            if (start >= 0 && end > start) {
                s = s.substring(start + 1, end).trim();
            }
        }
        int objStart = s.indexOf('{');
        int arrStart = s.indexOf('[');
        int begin;
        char close;
        if (arrStart >= 0 && (objStart < 0 || arrStart < objStart)) {
            begin = arrStart;
            close = ']';
        } else if (objStart >= 0) {
            begin = objStart;
            close = '}';
        } else {
            return s;
        }
        int last = s.lastIndexOf(close);
        if (last > begin) {
            return s.substring(begin, last + 1).trim();
        }
        return s.substring(begin).trim();
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
