package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class QueryMasteryToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KgNodeMapper nodeMapper = mock(KgNodeMapper.class);
    private final StudentMasteryMapper masteryMapper = mock(StudentMasteryMapper.class);
    private final MasteryDecayCalculator decayCalculator = mock(MasteryDecayCalculator.class);

    private QueryMasteryTool tool;

    @BeforeEach
    void setUp() {
        tool = new QueryMasteryTool(
                nodeMapper,
                masteryMapper,
                decayCalculator,
                objectMapper,
                new NodeToolSupport(nodeMapper));
    }

    @Test
    void overviewReturnsOnlyWeakItemsWithStableOrderAndZeroDefaults() throws Exception {
        KgNode kt01 = node(101L, 10L, "KT01", "Loops");
        KgNode kt02 = node(102L, 10L, "KT02", "Arrays");
        KgNode kt03 = node(103L, 10L, "KT03", "Recursion");
        KgNode kt04 = node(104L, 10L, "KT04", "Graphs");

        StudentMastery weak = mastery(1L, 10L, 101L, 88.0, 2, LocalDateTime.of(2026, 5, 28, 9, 30));
        StudentMastery shaky = mastery(1L, 10L, 103L, 62.0, 1, LocalDateTime.of(2026, 6, 20, 8, 0));
        StudentMastery mastered = mastery(1L, 10L, 104L, 95.0, 2, LocalDateTime.of(2026, 7, 1, 10, 15));

        when(nodeMapper.selectList(any())).thenReturn(List.of(kt01, kt02, kt03, kt04));
        when(masteryMapper.selectList(any())).thenReturn(List.of(weak, shaky, mastered));
        when(decayCalculator.effectiveScoreOf(weak)).thenReturn(35.0);
        when(decayCalculator.effectiveLevelOf(35.0)).thenReturn(0);
        when(decayCalculator.daysSinceEvidence(weak.getUpdatedAt())).thenReturn(40.0);
        when(decayCalculator.effectiveScoreOf(shaky)).thenReturn(55.0);
        when(decayCalculator.effectiveLevelOf(55.0)).thenReturn(1);
        when(decayCalculator.daysSinceEvidence(shaky.getUpdatedAt())).thenReturn(17.0);
        when(decayCalculator.effectiveScoreOf(mastered)).thenReturn(81.0);
        when(decayCalculator.effectiveLevelOf(81.0)).thenReturn(2);
        when(decayCalculator.daysSinceEvidence(mastered.getUpdatedAt())).thenReturn(6.0);

        JsonNode json = objectMapper.readTree(tool.execute(new ToolContext(1L, 10L), objectMapper.createObjectNode()));

        assertThat(json.path("items")).hasSize(3);
        assertThat(json.at("/items/0/nodeCode").asText()).isEqualTo("KT02");
        assertThat(json.at("/items/1/nodeCode").asText()).isEqualTo("KT01");
        assertThat(json.at("/items/2/nodeCode").asText()).isEqualTo("KT03");

        JsonNode noMastery = json.at("/items/0");
        assertThat(noMastery.path("name").asText()).isEqualTo("Arrays");
        assertThat(noMastery.path("rawScore").asDouble()).isZero();
        assertThat(noMastery.path("rawLevel").asInt()).isZero();
        assertThat(noMastery.path("effectiveScore").asDouble()).isZero();
        assertThat(noMastery.path("effectiveLevel").asInt()).isZero();
        assertThat(noMastery.path("daysSinceEvidence").isNull()).isTrue();
        assertThat(noMastery.path("lastEvidenceAt").isNull()).isTrue();

        assertThat(json.at("/items/1/rawScore").asDouble()).isEqualTo(88.0);
        assertThat(json.at("/items/1/rawLevel").asInt()).isEqualTo(2);
        assertThat(json.at("/items/1/effectiveScore").asDouble()).isEqualTo(35.0);
        assertThat(json.at("/items/1/effectiveLevel").asInt()).isEqualTo(0);
        assertThat(json.at("/items/1/daysSinceEvidence").asDouble()).isEqualTo(40.0);
        assertThat(json.at("/items/1/lastEvidenceAt").asText()).isEqualTo("2026-05-28T09:30:00");
    }

    @Test
    void overviewUsesDecayCalculatorForMasteryRecords() throws Exception {
        KgNode kt01 = node(101L, 10L, "KT01", "Loops");
        StudentMastery mastery = mastery(1L, 10L, 101L, 72.0, 1, LocalDateTime.of(2026, 6, 1, 8, 0));

        when(nodeMapper.selectList(any())).thenReturn(List.of(kt01));
        when(masteryMapper.selectList(any())).thenReturn(List.of(mastery));
        when(decayCalculator.effectiveScoreOf(mastery)).thenReturn(39.5);
        when(decayCalculator.effectiveLevelOf(39.5)).thenReturn(0);
        when(decayCalculator.daysSinceEvidence(mastery.getUpdatedAt())).thenReturn(36.0);

        tool.execute(new ToolContext(1L, 10L), objectMapper.createObjectNode());

        verify(decayCalculator).effectiveScoreOf(mastery);
        verify(decayCalculator).effectiveLevelOf(39.5);
        verify(decayCalculator).daysSinceEvidence(mastery.getUpdatedAt());
        verifyNoMoreInteractions(decayCalculator);
    }

    @Test
    void nodeCodeOutsideCourseThrowsToolException() {
        when(nodeMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT99")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("nodeCode")
                .hasMessageContaining("KT99");
    }

    @Test
    void specificNodeReturnsDetailJson() throws Exception {
        KgNode kt01 = node(101L, 10L, "KT01", "Loops");
        StudentMastery mastery = mastery(1L, 10L, 101L, 88.0, 2, LocalDateTime.of(2026, 5, 28, 9, 30));

        when(nodeMapper.selectOne(any())).thenReturn(kt01);
        when(masteryMapper.selectOne(any())).thenReturn(mastery);
        when(decayCalculator.effectiveScoreOf(mastery)).thenReturn(41.6);
        when(decayCalculator.effectiveLevelOf(41.6)).thenReturn(1);
        when(decayCalculator.daysSinceEvidence(mastery.getUpdatedAt())).thenReturn(40.0);

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT01")));

        assertThat(json.path("nodeCode").asText()).isEqualTo("KT01");
        assertThat(json.path("name").asText()).isEqualTo("Loops");
        assertThat(json.path("rawScore").asDouble()).isEqualTo(88.0);
        assertThat(json.path("rawLevel").asInt()).isEqualTo(2);
        assertThat(json.path("effectiveScore").asDouble()).isEqualTo(41.6);
        assertThat(json.path("effectiveLevel").asInt()).isEqualTo(1);
        assertThat(json.path("daysSinceEvidence").asDouble()).isEqualTo(40.0);
        assertThat(json.path("lastEvidenceAt").asText()).isEqualTo("2026-05-28T09:30:00");
    }

    @Test
    void specificNodeWithoutMasteryRowReturnsZeroDefaults() throws Exception {
        KgNode kt01 = node(101L, 10L, "KT01", "Loops");

        when(nodeMapper.selectOne(any())).thenReturn(kt01);
        when(masteryMapper.selectOne(any())).thenReturn(null);

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT01")));

        assertThat(json.path("nodeCode").asText()).isEqualTo("KT01");
        assertThat(json.path("name").asText()).isEqualTo("Loops");
        assertThat(json.path("rawScore").asDouble()).isZero();
        assertThat(json.path("rawLevel").asInt()).isZero();
        assertThat(json.path("effectiveScore").asDouble()).isZero();
        assertThat(json.path("effectiveLevel").asInt()).isZero();
        assertThat(json.path("daysSinceEvidence").isNull()).isTrue();
        assertThat(json.path("lastEvidenceAt").isNull()).isTrue();
        verifyNoInteractions(decayCalculator);
    }

    private KgNode node(Long id, Long courseId, String nodeCode, String name) {
        KgNode node = new KgNode();
        node.setId(id);
        node.setCourseId(courseId);
        node.setNodeCode(nodeCode);
        node.setName(name);
        return node;
    }

    private StudentMastery mastery(Long studentId,
                                   Long courseId,
                                   Long nodeId,
                                   double rawScore,
                                   int rawLevel,
                                   LocalDateTime updatedAt) {
        StudentMastery mastery = new StudentMastery();
        mastery.setStudentId(studentId);
        mastery.setCourseId(courseId);
        mastery.setNodeId(nodeId);
        mastery.setMasteryScore(BigDecimal.valueOf(rawScore));
        mastery.setMasteryLevel(rawLevel);
        mastery.setUpdatedAt(updatedAt);
        return mastery;
    }
}
