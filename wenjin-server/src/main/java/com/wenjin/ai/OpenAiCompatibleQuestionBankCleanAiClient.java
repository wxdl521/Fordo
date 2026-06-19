package com.wenjin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AiProperties;
import com.wenjin.dto.QuestionBankFile;
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
 * OpenAI 兼容的题库 AI 清洗客户端（默认 DeepSeek）。
 * <p>
 * 将 Excel 解析出的原始题目发送给 AI，修正格式、标准化字段。
 * prompt 构造与 JSON 解析做成包可见静态方法，便于无网络单元测试。
 */
@Component
public class OpenAiCompatibleQuestionBankCleanAiClient implements QuestionBankCleanAiClient {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleQuestionBankCleanAiClient(AiProperties properties) {
        this.properties = properties;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Override
    public QuestionBankFile clean(List<QuestionBankFile.BankQuestion> raw) {
        String prompt = buildCleanPrompt(raw);
        String content = call(prompt);
        try {
            return parseCleanResult(content);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR,
                    "AI服务调用失败：题库清洗结果解析失败 " + e.getMessage());
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
                "messages", List.of(
                        Map.of("role", "system", "content", "You are a data cleaning expert for educational question banks."),
                        Map.of("role", "user", "content", prompt)),
                "temperature", 0.2,
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

    static String buildCleanPrompt(List<QuestionBankFile.BankQuestion> raw) {
        StringBuilder sb = new StringBuilder();
        sb.append("请清洗以下从 Excel 解析出的题库数据。\n\n");
        sb.append("清洗规则：\n");
        sb.append("1. difficulty 标准化：确保为 1-5 的整数，不在范围内或缺失的修正为 3。\n");
        sb.append("2. 确保每道题至少有 2 个选项。\n");
        sb.append("3. 确保每道题恰好有一个选项标记为正确（correct=true）。\n");
        sb.append("4. 选项 key 标准化为大写 A/B/C/D。\n");
        sb.append("5. 去除 stem、option text、analysis 的首尾空白。\n");
        sb.append("6. nodeCode 格式校验：必须以 KT 开头，不合规的保留原值但在 note 中标注。\n");
        sb.append("7. 缺失 analysis 的用占位符 \"暂无解析\" 补充。\n");
        sb.append("8. 干扰项考点映射：对每个错误选项（correct=false），若它对应一种常见的错误理解，"
                + "给出导致该误解的「前置知识点编码」point_node_code（须以 KT 开头）；"
                + "无法判断则置为 null。正确选项的 point_node_code 一律为 null。\n\n");

        sb.append("以下是原始数据（JSON 格式）：\n");
        try {
            sb.append(MAPPER.writeValueAsString(raw));
        } catch (Exception e) {
            sb.append("[]");
        }

        sb.append("\n\n只返回一个 JSON 对象（不要任何多余文字、不要 Markdown 围栏），结构如下：\n");
        sb.append("{\"questions\":[{\"stem\":\"...\",\"nodeCode\":\"KT01\",\"chapter\":\"...\"," +
                "\"difficulty\":3,\"analysis\":\"...\",\"options\":[" +
                "{\"key\":\"A\",\"text\":\"...\",\"correct\":true,\"point_node_code\":null}]}]}\n");
        return sb.toString();
    }

    // ============================ JSON 解析（包可见，可测） ============================

    /**
     * 解析 AI 清洗结果 JSON 为 QuestionBankFile。
     */
    static QuestionBankFile parseCleanResult(String content) {
        String json = stripJsonFence(content);
        try {
            JsonNode root = MAPPER.readTree(json);

            QuestionBankFile result = new QuestionBankFile();
            List<QuestionBankFile.BankQuestion> questions = new ArrayList<>();

            JsonNode questionsNode = root.path("questions");
            if (questionsNode.isArray()) {
                for (JsonNode qn : questionsNode) {
                    QuestionBankFile.BankQuestion q = new QuestionBankFile.BankQuestion();
                    q.setStem(qn.path("stem").asText(null));
                    q.setNodeCode(qn.path("nodeCode").asText(null));
                    q.setChapter(qn.path("chapter").asText(null));

                    JsonNode diffNode = qn.path("difficulty");
                    if (!diffNode.isMissingNode() && !diffNode.isNull()) {
                        q.setDifficulty(diffNode.asInt(3));
                    }
                    q.setAnalysis(qn.path("analysis").asText(null));

                    List<QuestionBankFile.BankOption> options = new ArrayList<>();
                    JsonNode optionsNode = qn.path("options");
                    if (optionsNode.isArray()) {
                        for (JsonNode on : optionsNode) {
                            QuestionBankFile.BankOption opt = new QuestionBankFile.BankOption();
                            opt.setKey(on.path("key").asText(null));
                            opt.setText(on.path("text").asText(null));
                            opt.setCorrect(on.path("correct").asBoolean(false));
                            opt.setPointNodeCode(on.path("point_node_code").asText(null));
                            options.add(opt);
                        }
                    }
                    q.setOptions(options);
                    questions.add(q);
                }
            }
            result.setQuestions(questions);
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.AI_ERROR,
                    "AI服务调用失败：题库清洗结果解析失败 " + e.getMessage());
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
