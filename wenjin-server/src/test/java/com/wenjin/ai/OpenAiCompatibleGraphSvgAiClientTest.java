package com.wenjin.ai;

import com.wenjin.dto.TeacherGraphVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleGraphSvgAiClientTest {

    private TeacherGraphVO.NodeVO node(String code, String name, String chapter, int diff, boolean key) {
        TeacherGraphVO.NodeVO n = new TeacherGraphVO.NodeVO();
        n.setNodeCode(code); n.setName(name); n.setChapter(chapter);
        n.setDifficulty(diff); n.setIsKey(key);
        return n;
    }

    private TeacherGraphVO.EdgeVO edge(String s, String t, String type) {
        TeacherGraphVO.EdgeVO e = new TeacherGraphVO.EdgeVO();
        e.setSource(s); e.setTarget(t); e.setType(type);
        return e;
    }

    @Test
    void buildSvgPrompt_includesContractAndData() {
        String p = OpenAiCompatibleGraphSvgAiClient.buildSvgPrompt(
                List.of(node("K01", "用例图", "需求建模", 3, true)),
                List.of(edge("K01", "K02", "前置")));
        assertTrue(p.contains("viewBox=\"0 0 1480 740\""), "应写死 viewBox");
        assertTrue(p.contains("wj-node"), "节点圆应要求 class=wj-node");
        assertTrue(p.contains("stroke-dasharray"), "应说明包含边用虚线");
        assertTrue(p.contains("marker"), "应说明前置边用箭头 marker");
        assertTrue(p.contains("只输出") || p.contains("不要"), "应要求只输出 SVG");
        assertTrue(p.contains("K01") && p.contains("用例图"), "应注入图谱数据");
        assertTrue(p.contains("需求建模"), "应注入章节用于分区");
    }

    @Test
    void buildRepairPrompt_feedsIssuesBack() {
        String p = OpenAiCompatibleGraphSvgAiClient.buildRepairPrompt(
                "<svg></svg>", List.of("缺少 viewBox", "3 个节点坐标越界"));
        assertTrue(p.contains("缺少 viewBox"));
        assertTrue(p.contains("3 个节点坐标越界"));
        assertTrue(p.contains("<svg></svg>"), "应带上上一版 SVG");
    }

    @Test
    void stripCodeFence_extractsSvg() {
        assertEquals("<svg>x</svg>",
                OpenAiCompatibleGraphSvgAiClient.stripCodeFence("```svg\n<svg>x</svg>\n```"));
        assertEquals("<svg>y</svg>",
                OpenAiCompatibleGraphSvgAiClient.stripCodeFence("前言\n<svg>y</svg> 后语"));
    }
}
