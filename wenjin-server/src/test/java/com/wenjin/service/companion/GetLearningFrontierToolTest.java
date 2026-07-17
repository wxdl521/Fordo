package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.support.PrereqDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetLearningFrontierToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KgNodeMapper nodeMapper = mock(KgNodeMapper.class);
    private final KgEdgeMapper edgeMapper = mock(KgEdgeMapper.class);
    private final StudentMasteryMapper masteryMapper = mock(StudentMasteryMapper.class);
    private final MasteryDecayCalculator decayCalculator = mock(MasteryDecayCalculator.class);

    private GetLearningFrontierTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetLearningFrontierTool(
                nodeMapper, edgeMapper, masteryMapper, decayCalculator, objectMapper);
        when(decayCalculator.getMasteredThreshold()).thenReturn(75.0);
    }

    @Test
    void returnsNodesWithAllPrereqsMasteredButSelfNot() throws Exception {
        // A (mastered) -> B (not); C (no prereq, not); D depends on B (blocked)
        KgNode a = node(1L, "KT00", "A");
        KgNode b = node(2L, "KT01", "B");
        KgNode c = node(3L, "KT02", "C");
        KgNode d = node(4L, "KT03", "D");

        when(nodeMapper.selectList(any())).thenReturn(List.of(a, b, c, d));
        when(edgeMapper.selectList(any())).thenReturn(List.of(
                edge(10L, a.getId(), b.getId()),
                edge(11L, b.getId(), d.getId())));

        StudentMastery ma = mastery(1L, 90.0);
        StudentMastery mb = mastery(2L, 40.0);
        when(masteryMapper.selectList(any())).thenReturn(List.of(ma, mb));
        when(decayCalculator.effectiveScoreOf(ma)).thenReturn(88.0);
        when(decayCalculator.effectiveScoreOf(mb)).thenReturn(38.0);

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L), objectMapper.createObjectNode()));

        assertThat(json.path("items")).hasSize(2);
        // Sorted by effective then code: C (0), B (38)
        assertThat(json.at("/items/0/code").asText()).isEqualTo("KT02");
        assertThat(json.at("/items/0/effective").asDouble()).isZero();
        assertThat(json.at("/items/1/code").asText()).isEqualTo("KT01");
        assertThat(json.at("/items/1/effective").asDouble()).isEqualTo(38.0);
    }

    @Test
    void limitCapsResults() throws Exception {
        KgNode a = node(1L, "KT00", "A");
        KgNode b = node(2L, "KT01", "B");
        KgNode c = node(3L, "KT02", "C");
        when(nodeMapper.selectList(any())).thenReturn(List.of(a, b, c));
        when(edgeMapper.selectList(any())).thenReturn(List.of());
        when(masteryMapper.selectList(any())).thenReturn(List.of());

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("limit", 1)));

        assertThat(json.path("items")).hasSize(1);
    }

    private KgNode node(Long id, String code, String name) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setCourseId(10L);
        n.setNodeCode(code);
        n.setName(name);
        return n;
    }

    private KgEdge edge(Long id, Long from, Long to) {
        KgEdge e = new KgEdge();
        e.setId(id);
        e.setCourseId(10L);
        e.setFromNodeId(from);
        e.setToNodeId(to);
        e.setRelationType(PrereqDirection.RELATION_TYPE_PREREQUISITE);
        return e;
    }

    private StudentMastery mastery(Long nodeId, double score) {
        StudentMastery m = new StudentMastery();
        m.setNodeId(nodeId);
        m.setMasteryScore(BigDecimal.valueOf(score));
        m.setMasteryLevel(2);
        return m;
    }
}
