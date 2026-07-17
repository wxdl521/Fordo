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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TracePrerequisitesToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KgNodeMapper nodeMapper = mock(KgNodeMapper.class);
    private final KgEdgeMapper edgeMapper = mock(KgEdgeMapper.class);
    private final StudentMasteryMapper masteryMapper = mock(StudentMasteryMapper.class);
    private final MasteryDecayCalculator decayCalculator = mock(MasteryDecayCalculator.class);

    private TracePrerequisitesTool tool;

    @BeforeEach
    void setUp() {
        tool = new TracePrerequisitesTool(
                nodeMapper,
                edgeMapper,
                masteryMapper,
                decayCalculator,
                objectMapper,
                new NodeToolSupport(nodeMapper));
        when(masteryMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void bfsReturnsOrderedChainByDepthWithEffective() throws Exception {
        // A -> B -> C  (A prereq of B, B prereq of C). Focus = C.
        KgNode a = node(1L, "KT00", "A");
        KgNode b = node(2L, "KT01", "B");
        KgNode c = node(3L, "KT02", "C");

        when(nodeMapper.selectOne(any())).thenReturn(c);
        when(edgeMapper.selectList(any())).thenReturn(List.of(
                edge(10L, a.getId(), b.getId()),
                edge(11L, b.getId(), c.getId())));
        when(nodeMapper.selectBatchIds(any())).thenReturn(List.of(a, b, c));

        StudentMastery masteryB = mastery(2L, 70.0);
        when(masteryMapper.selectList(any())).thenReturn(List.of(masteryB));
        when(decayCalculator.effectiveScoreOf(masteryB)).thenReturn(65.0);

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT02")));

        assertThat(json.path("nodeCode").asText()).isEqualTo("KT02");
        assertThat(json.path("chain")).hasSize(2);
        assertThat(json.at("/chain/0/code").asText()).isEqualTo("KT01");
        assertThat(json.at("/chain/0/depth").asInt()).isEqualTo(1);
        assertThat(json.at("/chain/0/effective").asDouble()).isEqualTo(65.0);
        assertThat(json.at("/chain/1/code").asText()).isEqualTo("KT00");
        assertThat(json.at("/chain/1/depth").asInt()).isEqualTo(2);
        assertThat(json.at("/chain/1/effective").isNull()).isTrue();
    }

    @Test
    void maxDepthLimitsHops() throws Exception {
        KgNode a = node(1L, "KT00", "A");
        KgNode b = node(2L, "KT01", "B");
        KgNode c = node(3L, "KT02", "C");

        when(nodeMapper.selectOne(any())).thenReturn(c);
        when(edgeMapper.selectList(any())).thenReturn(List.of(
                edge(10L, a.getId(), b.getId()),
                edge(11L, b.getId(), c.getId())));
        when(nodeMapper.selectBatchIds(any())).thenReturn(List.of(a, b, c));

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT02").put("maxDepth", 1)));

        assertThat(json.path("chain")).hasSize(1);
        assertThat(json.at("/chain/0/code").asText()).isEqualTo("KT01");
        assertThat(json.at("/chain/0/depth").asInt()).isEqualTo(1);
    }

    @Test
    void missingNodeCodeThrows() {
        assertThatThrownBy(() -> tool.execute(new ToolContext(1L, 10L), objectMapper.createObjectNode()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("nodeCode");
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
        return m;
    }
}
