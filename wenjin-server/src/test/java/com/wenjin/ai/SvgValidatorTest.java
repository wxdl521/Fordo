package com.wenjin.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SvgValidatorTest {

    /** 合法 2 节点 1 边图：暗底 + viewBox + 2 个 wj-node + 虚线 + 箭头 + 章节标题。 */
    private String goodSvg() {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1480 740\">"
             + "<rect x=\"0\" y=\"0\" width=\"1480\" height=\"740\" fill=\"#121317\"/>"
             + "<defs><marker id=\"arrow\"><path d=\"M0,0 L6,3 L0,6 Z\"/></marker></defs>"
             + "<text x=\"300\" y=\"100\" fill=\"#9A948A\" font-size=\"21\">需求建模</text>"
             + "<line x1=\"200\" y1=\"200\" x2=\"600\" y2=\"300\" stroke=\"#9A948A\" marker-end=\"url(#arrow)\"/>"
             + "<line x1=\"200\" y1=\"200\" x2=\"600\" y2=\"300\" stroke=\"#999\" stroke-dasharray=\"5 5\"/>"
             + "<circle class=\"wj-node\" cx=\"200\" cy=\"200\" r=\"12\" fill=\"#D85E45\"/>"
             + "<circle class=\"wj-node\" cx=\"600\" cy=\"300\" r=\"10\" fill=\"#4A4D55\"/>"
             + "<text x=\"200\" y=\"222\">用例图</text><text x=\"600\" y=\"322\">领域模型</text>"
             + "</svg>";
    }

    @Test
    void validSvg_passes() {
        List<String> issues = SvgValidator.validate(goodSvg(), 2, 1, List.of("需求建模"));
        assertTrue(issues.isEmpty(), "应无违规，实际：" + issues);
    }

    @Test
    void malformedXml_reported() {
        List<String> issues = SvgValidator.validate("<svg><circle></svg>", 1, 0, List.of());
        assertFalse(issues.isEmpty());
        assertTrue(issues.get(0).contains("合法 XML"));
    }

    @Test
    void missingViewBox_reported() {
        String svg = goodSvg().replace(" viewBox=\"0 0 1480 740\"", "");
        List<String> issues = SvgValidator.validate(svg, 2, 1, List.of("需求建模"));
        assertTrue(issues.stream().anyMatch(s -> s.contains("viewBox")));
    }

    @Test
    void nodeCountMismatch_reported() {
        List<String> issues = SvgValidator.validate(goodSvg(), 5, 1, List.of("需求建模"));
        assertTrue(issues.stream().anyMatch(s -> s.contains("节点圆数量")));
    }

    @Test
    void outOfBounds_reported() {
        String svg = goodSvg().replace("cx=\"600\" cy=\"300\"", "cx=\"5000\" cy=\"300\"");
        List<String> issues = SvgValidator.validate(svg, 2, 1, List.of("需求建模"));
        assertTrue(issues.stream().anyMatch(s -> s.contains("越界")));
    }

    @Test
    void missingDashAndArrow_reported() {
        String svg = "<svg viewBox=\"0 0 1480 740\"><rect width=\"1480\" height=\"740\" fill=\"#121317\"/>"
                   + "<circle class=\"wj-node\" cx=\"200\" cy=\"200\" r=\"10\"/>"
                   + "<text x=\"200\" y=\"100\">需求建模</text></svg>";
        List<String> issues = SvgValidator.validate(svg, 1, 0, List.of("需求建模"));
        assertTrue(issues.stream().anyMatch(s -> s.contains("虚线")));
        assertTrue(issues.stream().anyMatch(s -> s.contains("箭头")));
    }

    @Test
    void missingChapterTitle_reported() {
        List<String> issues = SvgValidator.validate(goodSvg(), 2, 1, List.of("不存在的章节"));
        assertTrue(issues.stream().anyMatch(s -> s.contains("章节标题")));
    }
}
