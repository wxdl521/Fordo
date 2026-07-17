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
import com.wenjin.support.ReviewMarker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetNodeDetailToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KgNodeMapper nodeMapper = mock(KgNodeMapper.class);
    private final KgEdgeMapper edgeMapper = mock(KgEdgeMapper.class);
    private final StudentMasteryMapper masteryMapper = mock(StudentMasteryMapper.class);
    private final MasteryDecayCalculator decayCalculator = mock(MasteryDecayCalculator.class);

    private GetNodeDetailTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetNodeDetailTool(
                nodeMapper,
                edgeMapper,
                masteryMapper,
                decayCalculator,
                objectMapper,
                new NodeToolSupport(nodeMapper));
        when(masteryMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void missingOrBlankNodeCodeThrowsToolException() {
        assertThatThrownBy(() -> tool.execute(new ToolContext(1L, 10L), objectMapper.createObjectNode()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("nodeCode or nodeName is required");

        assertThatThrownBy(() -> tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "   ")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("nodeCode or nodeName is required");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaDisallowsUnknownArguments() {
        var function = (java.util.Map<String, Object>) tool.schema().get("function");
        var parameters = (java.util.Map<String, Object>) function.get("parameters");

        assertThat(parameters).containsEntry("additionalProperties", false);
        assertThat(parameters).containsKey("anyOf");
    }

    @Test
    void validNodeReturnsDetailPlusPrerequisitesAndSuccessorsWithEffective() throws Exception {
        KgNode focus = node(200L, 10L, "KT02", "Dynamic Programming");
        focus.setChapter("Chapter 3");
        focus.setDifficulty(3);
        focus.setIsKey(1);
        focus.setBloom("Analyze");
        focus.setDescription("Break problems into overlapping subproblems.");

        KgNode prereqB = node(110L, 10L, "KT01", "Recursion");
        KgNode prereqA = node(111L, 10L, "KT00", "Induction");
        KgNode successor = node(210L, 10L, "KT03", "State Compression");

        KgEdge aToFocus = edge(10L, 10L, prereqA.getId(), focus.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge bToFocus = edge(11L, 10L, prereqB.getId(), focus.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge focusToSuccessor = edge(12L, 10L, focus.getId(), successor.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);

        StudentMastery masteryA = mastery(111L, 80.0);
        StudentMastery masteryB = mastery(110L, 40.0);

        when(nodeMapper.selectOne(any())).thenReturn(focus);
        when(edgeMapper.selectList(any())).thenReturn(List.of(aToFocus, bToFocus, focusToSuccessor));
        when(nodeMapper.selectBatchIds(any())).thenReturn(List.of(focus, prereqB, prereqA, successor));
        when(masteryMapper.selectList(any())).thenReturn(List.of(masteryA, masteryB));
        when(decayCalculator.effectiveScoreOf(masteryA)).thenReturn(78.0);
        when(decayCalculator.effectiveScoreOf(masteryB)).thenReturn(38.0);

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT02")));

        assertThat(json.path("nodeCode").asText()).isEqualTo("KT02");
        assertThat(json.path("detail").path("nodeCode").asText()).isEqualTo("KT02");
        assertThat(json.path("detail").path("name").asText()).isEqualTo("Dynamic Programming");
        assertThat(json.path("detail").path("chapter").asText()).isEqualTo("Chapter 3");
        assertThat(json.path("detail").path("difficulty").asInt()).isEqualTo(3);
        assertThat(json.path("detail").path("isKey").asInt()).isEqualTo(1);
        assertThat(json.path("detail").path("bloom").asText()).isEqualTo("Analyze");
        assertThat(json.path("detail").path("description").asText()).isEqualTo("Break problems into overlapping subproblems.");
        assertThat(json.path("prerequisites")).hasSize(2);
        assertThat(json.at("/prerequisites/0/code").asText()).isEqualTo("KT00");
        assertThat(json.at("/prerequisites/0/name").asText()).isEqualTo("Induction");
        assertThat(json.at("/prerequisites/0/effective").asDouble()).isEqualTo(78.0);
        assertThat(json.at("/prerequisites/1/code").asText()).isEqualTo("KT01");
        assertThat(json.at("/prerequisites/1/effective").asDouble()).isEqualTo(38.0);
        assertThat(json.path("successors")).hasSize(1);
        assertThat(json.at("/successors/0/code").asText()).isEqualTo("KT03");
        assertThat(json.at("/successors/0/effective").isNull()).isTrue();
    }

    @Test
    void validUniqueNodeNameResolvesNodeDeterministically() throws Exception {
        KgNode focus = node(200L, 10L, "KT02", "Dynamic Programming");

        when(nodeMapper.selectList(any())).thenReturn(List.of(focus));
        when(edgeMapper.selectList(any())).thenReturn(List.of());
        when(nodeMapper.selectBatchIds(any())).thenReturn(List.of(focus));

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeName", "Dynamic Programming")));

        assertThat(json.path("nodeCode").asText()).isEqualTo("KT02");
        assertThat(json.path("name").asText()).isEqualTo("Dynamic Programming");
        assertThat(json.path("detail").path("nodeCode").asText()).isEqualTo("KT02");
    }

    @Test
    void ambiguousNodeNameThrowsToolException() {
        when(nodeMapper.selectList(any())).thenReturn(List.of(
                node(200L, 10L, "KT02", "Dynamic Programming"),
                node(201L, 10L, "KT20", "Dynamic Programming")));

        assertThatThrownBy(() -> tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeName", "Dynamic Programming")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void validNodeReturnsSuccessorsFromOutgoingPrerequisiteEdges() throws Exception {
        KgNode focus = node(200L, 10L, "KT02", "Dynamic Programming");
        KgNode prereq = node(110L, 10L, "KT01", "Recursion");
        KgNode successorB = node(220L, 10L, "KT04", "Digit DP");
        KgNode successorA = node(210L, 10L, "KT03", "State Compression");

        KgEdge prereqToFocus = edge(10L, 10L, prereq.getId(), focus.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge focusToA = edge(11L, 10L, focus.getId(), successorA.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge focusToB = edge(12L, 10L, focus.getId(), successorB.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);

        when(nodeMapper.selectOne(any())).thenReturn(focus);
        when(edgeMapper.selectList(any())).thenReturn(List.of(prereqToFocus, focusToB, focusToA));
        when(nodeMapper.selectBatchIds(any())).thenReturn(List.of(focus, prereq, successorB, successorA));

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT02")));

        assertThat(json.path("successors")).hasSize(2);
        assertThat(json.at("/successors/0/code").asText()).isEqualTo("KT03");
        assertThat(json.at("/successors/1/code").asText()).isEqualTo("KT04");
    }

    @Test
    void ignoresNonPrerequisiteRelationsAndOtherCourses() throws Exception {
        KgNode focus = node(200L, 10L, "KT02", "Dynamic Programming");
        KgNode prereq = node(110L, 10L, "KT01", "Recursion");
        KgNode successor = node(210L, 10L, "KT03", "State Compression");
        KgNode foreign = node(310L, 99L, "ZZ01", "Foreign");

        KgEdge goodIn = edge(10L, 10L, prereq.getId(), focus.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge goodOut = edge(11L, 10L, focus.getId(), successor.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge wrongType = edge(12L, 10L, foreign.getId(), focus.getId(), 3);
        KgEdge wrongCourse = edge(13L, 99L, focus.getId(), foreign.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);

        when(nodeMapper.selectOne(any())).thenReturn(focus);
        when(edgeMapper.selectList(any())).thenReturn(List.of(goodIn, goodOut, wrongType, wrongCourse));
        when(nodeMapper.selectBatchIds(any())).thenReturn(List.of(focus, prereq, successor));

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT02")));

        assertThat(json.path("prerequisites")).hasSize(1);
        assertThat(json.at("/prerequisites/0/code").asText()).isEqualTo("KT01");
        assertThat(json.path("successors")).hasSize(1);
        assertThat(json.at("/successors/0/code").asText()).isEqualTo("KT03");
    }

    @Test
    void pendingPrerequisiteEdgesAreExcludedFromPrerequisitesAndSuccessors() throws Exception {
        KgNode focus = node(200L, 10L, "KT02", "Dynamic Programming");
        KgNode visiblePrereq = node(110L, 10L, "KT01", "Recursion");
        KgNode pendingPrereq = node(111L, 10L, "KT00", "Induction");
        KgNode visibleSuccessor = node(210L, 10L, "KT03", "State Compression");
        KgNode pendingSuccessor = node(211L, 10L, "KT04", "Digit DP");

        KgEdge visibleIn = edge(10L, 10L, visiblePrereq.getId(), focus.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge pendingIn = edge(11L, 10L, pendingPrereq.getId(), focus.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        pendingIn.setRelationNote(ReviewMarker.PREFIX + "teacher review");
        KgEdge visibleOut = edge(12L, 10L, focus.getId(), visibleSuccessor.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge pendingOut = edge(13L, 10L, focus.getId(), pendingSuccessor.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        pendingOut.setRelationNote(ReviewMarker.PREFIX + "teacher review");

        when(nodeMapper.selectOne(any())).thenReturn(focus);
        when(edgeMapper.selectList(any())).thenReturn(List.of(visibleIn, pendingIn, visibleOut, pendingOut));
        when(nodeMapper.selectBatchIds(any())).thenReturn(List.of(focus, visiblePrereq, pendingPrereq, visibleSuccessor, pendingSuccessor));

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT02")));

        assertThat(json.path("prerequisites")).hasSize(1);
        assertThat(json.at("/prerequisites/0/code").asText()).isEqualTo("KT01");
        assertThat(json.path("successors")).hasSize(1);
        assertThat(json.at("/successors/0/code").asText()).isEqualTo("KT03");
    }

    private KgNode node(Long id, Long courseId, String nodeCode, String name) {
        KgNode node = new KgNode();
        node.setId(id);
        node.setCourseId(courseId);
        node.setNodeCode(nodeCode);
        node.setName(name);
        return node;
    }

    private KgEdge edge(Long id, Long courseId, Long fromNodeId, Long toNodeId, int relationType) {
        KgEdge edge = new KgEdge();
        edge.setId(id);
        edge.setCourseId(courseId);
        edge.setFromNodeId(fromNodeId);
        edge.setToNodeId(toNodeId);
        edge.setRelationType(relationType);
        return edge;
    }

    private StudentMastery mastery(Long nodeId, double score) {
        StudentMastery m = new StudentMastery();
        m.setNodeId(nodeId);
        m.setMasteryScore(BigDecimal.valueOf(score));
        m.setMasteryLevel(2);
        return m;
    }
}
