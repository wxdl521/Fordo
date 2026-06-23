package com.wenjin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AiProperties;
import com.wenjin.dto.TeacherGraphVO;
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
 * OpenAI 兼容的图谱预览 SVG 生成客户端（默认 DeepSeek）。
 * prompt 构造与围栏剥离做成包可见静态方法，便于无网络单测。
 */
@Component
public class OpenAiCompatibleGraphSvgAiClient implements GraphSvgAiClient {

    private final AiProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleGraphSvgAiClient(AiProperties properties) {
        this.properties = properties;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Override
    public String generate(String prompt) {
        return stripCodeFence(call(prompt));
    }

    String call(String prompt) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI 功能已禁用（wenjin.ai.enabled=false）");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BusinessException(ResultCode.AI_ERROR, "AI服务调用失败：未配置 API Key（wenjin.ai.api-key）");
        }
        String url = trimTrailingSlash(properties.getBaseUrl()) + "/v1/chat/completions";
        // 注意：SVG 文本响应，绝不设 response_format=json_object
        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", properties.getTemperature() == null ? 0.2 : properties.getTemperature());

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

    public static String buildSvgPrompt(List<TeacherGraphVO.NodeVO> nodes, List<TeacherGraphVO.EdgeVO> edges) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是知识图谱可视化专家。请把下面这份知识图谱画成一张完整、可读、美观的 SVG。\n\n");
        sb.append("严格遵守以下视觉契约：\n");
        sb.append("1. 根元素 <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1480 740\">，所有图元坐标必须落在该画布内。\n");
        sb.append("2. 暗色风格：先画一个铺满画布的背景矩形 fill=\"#121317\"；可点缀少量星点小圆。\n");
        sb.append("3. 章节分区：对每一个不同的 chapter，画一个衬线大标题 <text>（font-size 约 21、字距较大、fill=\"#9A948A\"），并把同章节的节点聚拢在该标题附近。\n");
        sb.append("4. 节点：用 <circle class=\"wj-node\"> 画，半径按重点更大、难度更高更大（约 7–17）；重点节点 fill=\"#D85E45\"，其余 fill=\"#4A4D55\"。每个节点配一个 <text> 标签（fill=\"#9A948A\"，font-size 约 11），标签紧贴节点且彼此不重叠、不被节点压住。\n");
        sb.append("5. 边按 type 区分：前置=实线 + marker-end 箭头；包含=stroke-dasharray 虚线；相关=细线、低透明。\n");
        sb.append("6. 只输出 SVG 本身，不要任何解释文字、不要 markdown 围栏。\n\n");

        sb.append("图谱数据（JSON）：\n节点：\n");
        sb.append(nodesJson(nodes));
        sb.append("\n边：\n");
        sb.append(edgesJson(edges));

        sb.append("\n\n格式样例（仅示意结构，请按真实数据与上面契约输出完整 SVG）：\n");
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1480 740\">");
        sb.append("<rect x=\"0\" y=\"0\" width=\"1480\" height=\"740\" fill=\"#121317\"/>");
        sb.append("<defs><marker id=\"arrow\" markerWidth=\"7\" markerHeight=\"7\" refX=\"6\" refY=\"3\" orient=\"auto\">");
        sb.append("<path d=\"M0,0 L6,3 L0,6 Z\" fill=\"#9A948A\"/></marker></defs>");
        sb.append("<text x=\"300\" y=\"120\" text-anchor=\"middle\" fill=\"#9A948A\" font-size=\"21\" letter-spacing=\"7\">需求建模</text>");
        sb.append("<line x1=\"300\" y1=\"200\" x2=\"460\" y2=\"260\" stroke=\"#9A948A\" stroke-width=\"1.4\" marker-end=\"url(#arrow)\"/>");
        sb.append("<line x1=\"460\" y1=\"260\" x2=\"600\" y2=\"260\" stroke=\"rgba(232,227,216,0.18)\" stroke-width=\"1.2\" stroke-dasharray=\"5 5\"/>");
        sb.append("<circle class=\"wj-node\" cx=\"300\" cy=\"200\" r=\"13\" fill=\"#D85E45\"/>");
        sb.append("<text x=\"300\" y=\"222\" text-anchor=\"middle\" fill=\"#9A948A\" font-size=\"11\">用例图</text>");
        sb.append("</svg>\n");
        return sb.toString();
    }

    public static String buildRepairPrompt(String prevSvg, List<String> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("你上一版生成的 SVG 存在以下问题：\n");
        for (String it : issues) {
            sb.append("- ").append(it).append("\n");
        }
        sb.append("\n请在保持原有暗色风格与视觉契约（viewBox 0 0 1480 740、节点 class=\"wj-node\"、");
        sb.append("三类边样式、章节标题、坐标不越界、标签不重叠）不变的前提下修正这些问题，");
        sb.append("重新输出完整 SVG，只输出 SVG，不要解释、不要 markdown 围栏。\n\n");
        sb.append("上一版 SVG：\n").append(prevSvg).append("\n");
        return sb.toString();
    }

    private static String nodesJson(List<TeacherGraphVO.NodeVO> nodes) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < nodes.size(); i++) {
            TeacherGraphVO.NodeVO n = nodes.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"nodeCode\":").append(quote(n.getNodeCode()))
              .append(",\"name\":").append(quote(n.getName()))
              .append(",\"chapter\":").append(quote(n.getChapter()))
              .append(",\"difficulty\":").append(n.getDifficulty() == null ? 3 : n.getDifficulty())
              .append(",\"isKey\":").append(Boolean.TRUE.equals(n.getIsKey()))
              .append("}");
        }
        return sb.append("]").toString();
    }

    private static String edgesJson(List<TeacherGraphVO.EdgeVO> edges) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < edges.size(); i++) {
            TeacherGraphVO.EdgeVO e = edges.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"source\":").append(quote(e.getSource()))
              .append(",\"target\":").append(quote(e.getTarget()))
              .append(",\"type\":").append(quote(e.getType()))
              .append("}");
        }
        return sb.append("]").toString();
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ============================ 工具方法 ============================

    /** 剥 markdown 围栏并截取首个 <svg ...></svg>。 */
    static String stripCodeFence(String content) {
        if (content == null) return "";
        String s = content.trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int start = s.indexOf('\n', fence);
            int fenceEnd = s.indexOf("```", fence + 3);
            if (start >= 0 && fenceEnd > start) {
                s = s.substring(start + 1, fenceEnd).trim();
            }
        }
        int begin = s.indexOf("<svg");
        int end = s.lastIndexOf("</svg>");
        if (begin >= 0 && end > begin) {
            return s.substring(begin, end + "</svg>".length()).trim();
        }
        return s;
    }

    private static String trimTrailingSlash(String base) {
        if (base == null) return "";
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
}
