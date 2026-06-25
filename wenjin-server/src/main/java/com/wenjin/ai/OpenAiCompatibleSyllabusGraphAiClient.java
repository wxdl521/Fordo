package com.wenjin.ai;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleSyllabusGraphAiClient implements SyllabusGraphAiClient {

    private final AiProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleSyllabusGraphAiClient(AiProperties properties) {
        this.properties = properties;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(120));
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Override
    public GraphImportRequest extract(String syllabusText) {
        if (!StringUtils.hasText(syllabusText)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "课程标准文本为空,无法抽取");
        }
        String content = call(buildExtractPrompt(syllabusText));
        return OpenAiCompatibleGraphCleanAiClient.parseCleanResult(content); // 复用解析(同包)
    }

    String call(String prompt) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI 功能已禁用(wenjin.ai.enabled=false)");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：未配置 API Key");
        }
        String url = trimTrailingSlash(properties.getBaseUrl()) + "/v1/chat/completions";
        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", properties.getTemperature() == null ? 0.2 : properties.getTemperature(),
                "response_format", Map.of("type", "json_object"));
        JsonNode root;
        try {
            root = restClient.post().uri(url)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：" + e.getMessage());
        }
        if (root == null || !root.has("choices") || !root.get("choices").isArray() || root.get("choices").isEmpty()) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：返回结构异常(缺少 choices)");
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：返回结构异常(缺少 message.content)");
        }
        return content.asText();
    }

    static String buildExtractPrompt(String syllabusText) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是软件工程课程的知识图谱构建专家。下面是某课程标准/教学大纲文本(可能含 Markdown 表格)。\n");
        sb.append("请抽取知识点并构建知识图谱草稿。\n\n规则：\n");
        sb.append("1. 节点:每个知识点一个。id 用大写字母+两位序号(如 KT01、KT02);name 知识点名称;chapter 所属章节;");
        sb.append("difficulty 取 1-5 整数(缺省 3);is_key 是否核心(true/false);");
        sb.append("bloom 取 记忆/理解/应用/分析/评价/创造 之一;description 一句话简介。\n");
        sb.append("2. 包含边:章节与其下知识点之间用 type=\"包含\"。\n");
        sb.append("3. 前置边:按章节先后与知识依赖推断 type=\"前置\"。\n");
        sb.append("4. 关系 type 仅限:前置/包含/相关/应用。\n");
        sb.append("5. 凡是由你推断(而非文本明确写出)的边,note 必须以『待复核』开头,");
        sb.append("例如 \"『待复核』按章节顺序推断\";文本中明确写出的关系 note 留空。\n\n");
        sb.append("只返回一个 JSON 对象(不要 Markdown 围栏、不要多余文字):\n");
        sb.append("{\"nodes\":[{\"id\":\"KT01\",\"name\":\"...\",\"chapter\":\"...\",\"difficulty\":3,");
        sb.append("\"is_key\":false,\"bloom\":\"理解\",\"description\":\"...\",\"note\":\"\"}],");
        sb.append("\"edges\":[{\"source\":\"KT01\",\"target\":\"KT02\",\"type\":\"前置\",\"note\":\"『待复核』按章节顺序推断\"}]}\n\n");
        sb.append("课程标准文本如下:\n");
        sb.append(syllabusText);
        return sb.toString();
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
