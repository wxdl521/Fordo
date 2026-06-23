package com.wenjin.service;

import com.wenjin.ai.GraphSvgAiClient;
import com.wenjin.dto.GraphPreviewResult;
import com.wenjin.dto.TeacherGraphVO;
import com.wenjin.service.impl.GraphPreviewServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GraphPreviewServiceImplTest {

    private TeacherGraphVO graph() {
        TeacherGraphVO g = new TeacherGraphVO();
        TeacherGraphVO.NodeVO n1 = new TeacherGraphVO.NodeVO();
        n1.setNodeCode("K01"); n1.setName("用例图"); n1.setChapter("需求建模");
        n1.setDifficulty(3); n1.setIsKey(true);
        TeacherGraphVO.NodeVO n2 = new TeacherGraphVO.NodeVO();
        n2.setNodeCode("K02"); n2.setName("领域模型"); n2.setChapter("需求建模");
        n2.setDifficulty(2); n2.setIsKey(false);
        TeacherGraphVO.EdgeVO e = new TeacherGraphVO.EdgeVO();
        e.setSource("K01"); e.setTarget("K02"); e.setType("前置");
        g.setNodes(List.of(n1, n2)); g.setEdges(List.of(e));
        return g;
    }

    /** 合法 2 节点 1 边 SVG（与 SvgValidatorTest.goodSvg 同构）。 */
    private String goodSvg() {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1480 740\">"
             + "<rect width=\"1480\" height=\"740\" fill=\"#121317\"/>"
             + "<defs><marker id=\"arrow\"><path d=\"M0,0 L6,3 L0,6 Z\"/></marker></defs>"
             + "<text x=\"300\" y=\"100\" font-size=\"21\">需求建模</text>"
             + "<line x1=\"200\" y1=\"200\" x2=\"600\" y2=\"300\" marker-end=\"url(#arrow)\"/>"
             + "<line x1=\"200\" y1=\"200\" x2=\"600\" y2=\"300\" stroke-dasharray=\"5 5\"/>"
             + "<circle class=\"wj-node\" cx=\"200\" cy=\"200\" r=\"12\"/>"
             + "<circle class=\"wj-node\" cx=\"600\" cy=\"300\" r=\"10\"/>"
             + "<text x=\"200\" y=\"222\">用例图</text><text x=\"600\" y=\"322\">领域模型</text></svg>";
    }

    @Test
    void firstRoundValid_sourceAi() {
        TeacherGraphService gs = mock(TeacherGraphService.class);
        GraphSvgAiClient ai = mock(GraphSvgAiClient.class);
        when(gs.getGraph(1L)).thenReturn(graph());
        when(ai.generate(anyString())).thenReturn(goodSvg());

        GraphPreviewResult r = new GraphPreviewServiceImpl(gs, ai).generatePreviewSvg(1L);
        assertTrue(r.isValid());
        assertEquals("ai", r.getSource());
        assertEquals(1, r.getRounds());
        verify(ai, times(1)).generate(anyString());
    }

    @Test
    void firstBadSecondGood_sourceRepaired() {
        TeacherGraphService gs = mock(TeacherGraphService.class);
        GraphSvgAiClient ai = mock(GraphSvgAiClient.class);
        when(gs.getGraph(1L)).thenReturn(graph());
        when(ai.generate(anyString())).thenReturn("<svg></svg>", goodSvg());

        GraphPreviewResult r = new GraphPreviewServiceImpl(gs, ai).generatePreviewSvg(1L);
        assertTrue(r.isValid());
        assertEquals("ai-repaired", r.getSource());
        assertEquals(2, r.getRounds());
        verify(ai, times(2)).generate(anyString());
    }

    @Test
    void bothBad_invalid() {
        TeacherGraphService gs = mock(TeacherGraphService.class);
        GraphSvgAiClient ai = mock(GraphSvgAiClient.class);
        when(gs.getGraph(1L)).thenReturn(graph());
        when(ai.generate(anyString())).thenReturn("<svg></svg>");

        GraphPreviewResult r = new GraphPreviewServiceImpl(gs, ai).generatePreviewSvg(1L);
        assertFalse(r.isValid());
        assertNull(r.getSource());
        assertFalse(r.getIssues().isEmpty());
    }

    @Test
    void aiThrows_invalidNotPropagated() {
        TeacherGraphService gs = mock(TeacherGraphService.class);
        GraphSvgAiClient ai = mock(GraphSvgAiClient.class);
        when(gs.getGraph(1L)).thenReturn(graph());
        when(ai.generate(anyString())).thenThrow(new RuntimeException("AI 挂了"));

        GraphPreviewResult r = new GraphPreviewServiceImpl(gs, ai).generatePreviewSvg(1L);
        assertFalse(r.isValid());
        assertTrue(r.getIssues().stream().anyMatch(s -> s.contains("AI")));
    }
}
