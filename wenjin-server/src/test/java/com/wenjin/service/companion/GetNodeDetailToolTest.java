package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.support.PrereqDirection;
import com.wenjin.support.ReviewMarker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private GetNodeDetailTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetNodeDetailTool(
                nodeMapper,
                edgeMapper,
                objectMapper,
                new NodeToolSupport(nodeMapper));
    }

    @Test
    void missingOrBlankNodeCodeThrowsToolException() {
        assertThatThrownBy(() -> tool.execute(new ToolContext(1L, 10L), objectMapper.createObjectNode()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("nodeCode is required");

        assertThatThrownBy(() -> tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "   ")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("nodeCode is required");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaDisallowsUnknownArguments() {
        var function = (java.util.Map<String, Object>) tool.schema().get("function");
        var parameters = (java.util.Map<String, Object>) function.get("parameters");

        assertThat(parameters).containsEntry("additionalProperties", false);
    }

    @Test
    void validNodeReturnsNodeFieldsAndPrerequisites() throws Exception {
        KgNode focus = node(200L, 10L, "KT02", "Dynamic Programming");
        focus.setChapter("Chapter 3");
        focus.setDifficulty(3);
        focus.setIsKey(1);
        focus.setBloom("Analyze");
        focus.setDescription("Break problems into overlapping subproblems.");

        KgNode prereqB = node(110L, 10L, "KT01", "Recursion");
        KgNode prereqA = node(111L, 10L, "KT00", "Induction");
        KgNode dependent = node(210L, 10L, "KT03", "State Compression");

        KgEdge aToFocus = edge(10L, 10L, prereqA.getId(), focus.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge bToFocus = edge(11L, 10L, prereqB.getId(), focus.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge focusToDependent = edge(12L, 10L, focus.getId(), dependent.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);

        when(nodeMapper.selectOne(any())).thenReturn(focus);
        when(edgeMapper.selectList(any())).thenReturn(List.of(aToFocus, bToFocus, focusToDependent));
        when(nodeMapper.selectBatchIds(any())).thenReturn(List.of(prereqB, prereqA, dependent));

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT02")));

        assertThat(json.path("nodeCode").asText()).isEqualTo("KT02");
        assertThat(json.path("name").asText()).isEqualTo("Dynamic Programming");
        assertThat(json.path("chapter").asText()).isEqualTo("Chapter 3");
        assertThat(json.path("difficulty").asInt()).isEqualTo(3);
        assertThat(json.path("isKey").asInt()).isEqualTo(1);
        assertThat(json.path("bloom").asText()).isEqualTo("Analyze");
        assertThat(json.path("description").asText()).isEqualTo("Break problems into overlapping subproblems.");
        assertThat(json.path("prerequisites")).hasSize(2);
        assertThat(json.at("/prerequisites/0/nodeCode").asText()).isEqualTo("KT00");
        assertThat(json.at("/prerequisites/1/nodeCode").asText()).isEqualTo("KT01");
    }

    @Test
    void validNodeReturnsDependentsFromOutgoingPrerequisiteEdges() throws Exception {
        KgNode focus = node(200L, 10L, "KT02", "Dynamic Programming");
        KgNode prereq = node(110L, 10L, "KT01", "Recursion");
        KgNode dependentB = node(220L, 10L, "KT04", "Digit DP");
        KgNode dependentA = node(210L, 10L, "KT03", "State Compression");

        KgEdge prereqToFocus = edge(10L, 10L, prereq.getId(), focus.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge focusToDependentA = edge(11L, 10L, focus.getId(), dependentA.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge focusToDependentB = edge(12L, 10L, focus.getId(), dependentB.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);

        when(nodeMapper.selectOne(any())).thenReturn(focus);
        when(edgeMapper.selectList(any())).thenReturn(List.of(prereqToFocus, focusToDependentB, focusToDependentA));
        when(nodeMapper.selectBatchIds(any())).thenReturn(List.of(prereq, dependentB, dependentA));

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT02")));

        assertThat(json.path("dependents")).hasSize(2);
        assertThat(json.at("/dependents/0/nodeCode").asText()).isEqualTo("KT03");
        assertThat(json.at("/dependents/1/nodeCode").asText()).isEqualTo("KT04");
    }

    @Test
    void ignoresNonPrerequisiteRelationsAndOtherCourses() throws Exception {
        KgNode focus = node(200L, 10L, "KT02", "Dynamic Programming");
        KgNode prereq = node(110L, 10L, "KT01", "Recursion");
        KgNode dependent = node(210L, 10L, "KT03", "State Compression");
        KgNode foreign = node(310L, 99L, "ZZ01", "Foreign");

        KgEdge goodIn = edge(10L, 10L, prereq.getId(), focus.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge goodOut = edge(11L, 10L, focus.getId(), dependent.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge wrongType = edge(12L, 10L, foreign.getId(), focus.getId(), 3);
        KgEdge wrongCourse = edge(13L, 99L, focus.getId(), foreign.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);

        when(nodeMapper.selectOne(any())).thenReturn(focus);
        when(edgeMapper.selectList(any())).thenReturn(List.of(goodIn, goodOut, wrongType, wrongCourse));
        when(nodeMapper.selectBatchIds(any())).thenReturn(List.of(prereq, dependent));

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT02")));

        assertThat(json.path("prerequisites")).hasSize(1);
        assertThat(json.at("/prerequisites/0/nodeCode").asText()).isEqualTo("KT01");
        assertThat(json.path("dependents")).hasSize(1);
        assertThat(json.at("/dependents/0/nodeCode").asText()).isEqualTo("KT03");
    }

    @Test
    void pendingPrerequisiteEdgesAreExcludedFromPrerequisitesAndDependents() throws Exception {
        KgNode focus = node(200L, 10L, "KT02", "Dynamic Programming");
        KgNode visiblePrereq = node(110L, 10L, "KT01", "Recursion");
        KgNode pendingPrereq = node(111L, 10L, "KT00", "Induction");
        KgNode visibleDependent = node(210L, 10L, "KT03", "State Compression");
        KgNode pendingDependent = node(211L, 10L, "KT04", "Digit DP");

        KgEdge visibleIn = edge(10L, 10L, visiblePrereq.getId(), focus.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge pendingIn = edge(11L, 10L, pendingPrereq.getId(), focus.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        pendingIn.setRelationNote(ReviewMarker.PREFIX + "teacher review");
        KgEdge visibleOut = edge(12L, 10L, focus.getId(), visibleDependent.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        KgEdge pendingOut = edge(13L, 10L, focus.getId(), pendingDependent.getId(), PrereqDirection.RELATION_TYPE_PREREQUISITE);
        pendingOut.setRelationNote(ReviewMarker.PREFIX + "teacher review");

        when(nodeMapper.selectOne(any())).thenReturn(focus);
        when(edgeMapper.selectList(any())).thenReturn(List.of(visibleIn, pendingIn, visibleOut, pendingOut));
        when(nodeMapper.selectBatchIds(any())).thenReturn(List.of(visiblePrereq, pendingPrereq, visibleDependent, pendingDependent));

        JsonNode json = objectMapper.readTree(tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT02")));

        assertThat(json.path("prerequisites")).hasSize(1);
        assertThat(json.at("/prerequisites/0/nodeCode").asText()).isEqualTo("KT01");
        assertThat(json.path("dependents")).hasSize(1);
        assertThat(json.at("/dependents/0/nodeCode").asText()).isEqualTo("KT03");
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
}
