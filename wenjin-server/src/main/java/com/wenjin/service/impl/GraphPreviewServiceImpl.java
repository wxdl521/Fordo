package com.wenjin.service.impl;

import com.wenjin.ai.GraphSvgAiClient;
import com.wenjin.ai.SvgValidator;
import com.wenjin.dto.GraphPreviewResult;
import com.wenjin.dto.TeacherGraphVO;
import com.wenjin.service.GraphPreviewService;
import com.wenjin.service.TeacherGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.wenjin.ai.OpenAiCompatibleGraphSvgAiClient.buildRepairPrompt;
import static com.wenjin.ai.OpenAiCompatibleGraphSvgAiClient.buildSvgPrompt;

/** AI 图谱预览 SVG 服务实现：建 prompt → 调 AI → 校验 → 修复重试（≤2 轮）。 */
@Service
public class GraphPreviewServiceImpl implements GraphPreviewService {

    private static final Logger log = LoggerFactory.getLogger(GraphPreviewServiceImpl.class);
    private static final int MAX_ROUNDS = 2;

    private final TeacherGraphService teacherGraphService;
    private final GraphSvgAiClient svgAiClient;

    public GraphPreviewServiceImpl(TeacherGraphService teacherGraphService, GraphSvgAiClient svgAiClient) {
        this.teacherGraphService = teacherGraphService;
        this.svgAiClient = svgAiClient;
    }

    @Override
    public GraphPreviewResult generatePreviewSvg(Long courseId) {
        GraphPreviewResult result = new GraphPreviewResult();

        TeacherGraphVO graph = teacherGraphService.getGraph(courseId);
        List<TeacherGraphVO.NodeVO> nodes = graph.getNodes() == null ? List.of() : graph.getNodes();
        List<TeacherGraphVO.EdgeVO> edges = graph.getEdges() == null ? List.of() : graph.getEdges();
        if (nodes.isEmpty()) {
            result.setValid(false);
            result.getIssues().add("该课程图谱无节点，无法生成预览");
            return result;
        }
        List<String> chapters = distinctChapters(nodes);

        String prompt = buildSvgPrompt(nodes, edges);
        String svg = null;
        List<String> issues = new ArrayList<>();
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            result.setRounds(round);
            try {
                svg = svgAiClient.generate(prompt);
            } catch (Exception e) {
                log.warn("预览 SVG AI 调用失败 round={} : {}", round, e.getMessage());
                result.setValid(false);
                result.setSource(null);
                result.setSvg(null);
                result.getIssues().clear();
                result.getIssues().add("AI 调用失败：" + e.getMessage());
                return result; // 异常直接交前端兜底
            }
            issues = SvgValidator.validate(svg, nodes.size(), edges.size(), chapters);
            if (issues.isEmpty()) {
                result.setSvg(svg);
                result.setValid(true);
                result.setSource(round == 1 ? "ai" : "ai-repaired");
                return result;
            }
            prompt = buildRepairPrompt(svg, issues); // 下一轮回灌违规项
        }
        // 轮数耗尽仍不合格：带回最后一版 + 违规项，交前端兜底
        result.setSvg(svg);
        result.setValid(false);
        result.setSource(null);
        result.setIssues(issues);
        return result;
    }

    private static List<String> distinctChapters(List<TeacherGraphVO.NodeVO> nodes) {
        Set<String> set = new LinkedHashSet<>();
        for (TeacherGraphVO.NodeVO n : nodes) {
            if (n.getChapter() != null && !n.getChapter().isBlank()) set.add(n.getChapter());
        }
        return new ArrayList<>(set);
    }
}
