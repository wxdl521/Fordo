package com.wenjin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.wenjin.ai.dto.AiAnnotation;
import com.wenjin.ai.dto.AiQuestion;
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
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的题目 AI 客户端真实现（默认 DeepSeek）。
 * <p>
 * prompt 构造与 JSON 解析做成包可见的静态方法，便于无网络单元测试。
 * 网络调用使用 spring-web 自带的 {@link RestClient}，无需引入新依赖。
 */
@Component
public class OpenAiCompatibleQuestionAiClient implements QuestionAiClient {

    /** 解析 AI 返回 JSON 用的 ObjectMapper：snake_case → camelCase（独立实例，不影响 Spring 全局 mapper） */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final AiProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleQuestionAiClient(AiProperties properties) {
        this.properties = properties;
        // 加上连接/读取超时，避免 AI 端点无响应时把请求线程（T3/T4 在请求线程上调用）挂死。
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    // ============================ 对外接口 ============================

    @Override
    public List<AiQuestion> generate(String targetNodeCode, String targetName,
            String chapter, int count, List<String[]> whitelist) {
        String prompt = buildGeneratePrompt(targetNodeCode, targetName, chapter, count, whitelist);
        String content = call(prompt);
        try {
            return parseQuestions(content);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：出题结果解析失败 " + e.getMessage());
        }
    }

    @Override
    public AiAnnotation annotate(String stem, List<String[]> options, List<String[]> whitelist) {
        String prompt = buildAnnotatePrompt(stem, options, whitelist);
        String content = call(prompt);
        try {
            return parseAnnotation(content);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：标注结果解析失败 " + e.getMessage());
        }
    }

    // ============================ 网络调用 ============================

    /**
     * 调用 OpenAI 兼容的 chat/completions，取出 choices[0].message.content。
     * 失败（缺 apiKey / 网络异常 / 结构异常）统一抛 {@link ResultCode#AI_ERROR}。
     */
    private String call(String prompt) {
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
                "temperature", properties.getTemperature() == null ? 0.4 : properties.getTemperature(),
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

    /**
     * 出题 Prompt（Prompt 2）：仅在白名单内出单选题，返回 {"questions":[...]} JSON。
     */
    static String buildGeneratePrompt(String targetCode, String targetName, String chapter,
            int count, List<String[]> whitelist) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是软件工程课程的命题专家。请围绕目标知识点出").append(count).append("道单选题。\n\n");
        sb.append("目标知识点：").append(targetCode).append("（").append(targetName).append("）");
        if (StringUtils.hasText(chapter)) {
            sb.append("，所属章节：").append(chapter);
        }
        sb.append("。\n\n");

        sb.append("知识点白名单（仅允许在以下范围内命题与标注考点，code 名称如下）：\n");
        sb.append(renderWhitelist(whitelist));
        sb.append('\n');

        sb.append("硬性约束：\n");
        sb.append("1. 题型必须是单选题；每题恰一个选项 correct=true，其余 correct=false。\n");
        sb.append("2. 只在上述白名单范围内命题，考点不得超出白名单。\n");
        sb.append("3. main_point 必须等于目标知识点 ").append(targetCode)
                .append("；sub_points 必须是白名单内的 code 子集。\n");
        sb.append("4. 每个干扰项（correct=false）应尽量给出其错误所对应的考点 point_node_code，"
                + "且该 code 必须在白名单内；正确项的 point_node_code 置为 null。\n");
        sb.append("5. difficulty 取 1–5 的整数。\n\n");

        sb.append("只返回一个 JSON 对象（不要任何多余文字、不要 Markdown 围栏），结构如下：\n");
        sb.append("{\"questions\":[\n");
        sb.append("  {\"stem\":\"题干\",\n");
        sb.append("   \"options\":[{\"key\":\"A\",\"text\":\"选项\",\"correct\":true,\"point_node_code\":null},\n");
        sb.append("              {\"key\":\"B\",\"text\":\"选项\",\"correct\":false,\"point_node_code\":\"白名单内的code\"}],\n");
        sb.append("   \"analysis\":\"解析\",\"difficulty\":3,\n");
        sb.append("   \"main_point\":\"").append(targetCode).append("\",\"sub_points\":[\"白名单内的code\"]}\n");
        sb.append("]}\n");
        return sb.toString();
    }

    /**
     * 标注 Prompt（Prompt 3）：在白名单内标注；超纲则 main_point=null 并在 reason 说明，不强行标注。
     */
    static String buildAnnotatePrompt(String stem, List<String[]> options, List<String[]> whitelist) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是软件工程课程的考点标注专家。请判断下面这道题考查的知识点，并映射到白名单内的 code。\n\n");

        sb.append("题干：").append(stem).append('\n');
        sb.append("选项：\n");
        if (options != null) {
            for (String[] opt : options) {
                String key = opt.length > 0 ? opt[0] : "";
                String text = opt.length > 1 ? opt[1] : "";
                sb.append("  ").append(key).append(". ").append(text).append('\n');
            }
        }
        sb.append('\n');

        sb.append("知识点白名单（只能在以下范围内标注考点，code 名称如下）：\n");
        sb.append(renderWhitelist(whitelist));
        sb.append('\n');

        sb.append("标注规则：\n");
        sb.append("1. main_point 为该题主考点的 code，必须在白名单内；sub_points 为白名单内的次考点 code 列表。\n");
        sb.append("2. distractors 为各干扰项错误所对应的考点：每项 {\"key\":\"选项标识\",\"point_node_code\":\"白名单内的code\"}。\n");
        sb.append("3. 若该题超纲（主考点不在白名单/课程范围内），则 main_point 置为 null，"
                + "并在 reason 中说明超纲原因，切勿强行标注。\n\n");

        sb.append("只返回一个 JSON 对象（不要任何多余文字、不要 Markdown 围栏），结构如下：\n");
        sb.append("{\"main_point\":\"白名单内的code或null\",\"sub_points\":[\"code\"],");
        sb.append("\"distractors\":[{\"key\":\"B\",\"point_node_code\":\"code\"}],\"reason\":\"理由\"}\n");
        return sb.toString();
    }

    /** 渲染白名单为 “- code 名称” 的多行列表。 */
    private static String renderWhitelist(List<String[]> whitelist) {
        StringBuilder sb = new StringBuilder();
        if (whitelist != null) {
            for (String[] item : whitelist) {
                String code = item.length > 0 ? item[0] : "";
                String name = item.length > 1 ? item[1] : "";
                sb.append("- ").append(code).append(' ').append(name).append('\n');
            }
        }
        return sb.toString();
    }

    // ============================ JSON 解析（包可见，可测） ============================

    /**
     * 解析出题返回：兼容 {"questions":[...]} 外层对象与裸数组 [...]，并自动剥离 Markdown ```json 围栏。
     */
    static List<AiQuestion> parseQuestions(String content) {
        String json = stripJsonFence(content);
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode arrayNode;
            if (root.isArray()) {
                arrayNode = root;
            } else if (root.has("questions") && root.get("questions").isArray()) {
                arrayNode = root.get("questions");
            } else {
                throw new BusinessException(ResultCode.AI_ERROR,
                        "AI服务调用失败：出题结果缺少 questions 数组");
            }
            CollectionType type = MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, AiQuestion.class);
            return MAPPER.convertValue(arrayNode, type);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR,
                    "AI服务调用失败：出题结果解析失败 " + e.getMessage());
        }
    }

    /**
     * 解析标注返回：单个 JSON 对象，自动剥离 Markdown ```json 围栏。
     */
    static AiAnnotation parseAnnotation(String content) {
        String json = stripJsonFence(content);
        try {
            return MAPPER.readValue(json, AiAnnotation.class);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR,
                    "AI服务调用失败：标注结果解析失败 " + e.getMessage());
        }
    }

    // ============================ 工具方法 ============================

    /**
     * 剥离 Markdown 代码围栏，截取第一个 JSON 对象/数组的内容。
     * 兼容 ```json ... ```、``` ... ``` 以及前后夹带说明文字的情况。
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
        // 进一步截取首个 { 或 [ 到对应的最后一个 } 或 ]，去掉残留前后文字
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
