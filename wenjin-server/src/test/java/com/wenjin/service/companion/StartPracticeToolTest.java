package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.service.PracticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StartPracticeToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PracticeService practiceService = mock(PracticeService.class);
    private final KgNodeMapper nodeMapper = mock(KgNodeMapper.class);

    private StartPracticeTool tool;

    @BeforeEach
    void setUp() {
        tool = new StartPracticeTool(
                practiceService,
                objectMapper,
                new NodeToolSupport(nodeMapper));
    }

    @Test
    void resolvesNodeCodeWithinCourseAndReturnsOnlySessionSummary() throws Exception {
        KgNode node = node(55L, 10L, "KT12", "Quadratic Functions");
        PracticeStartVO card = practiceCard(node, 901L, question("What shape is the graph?", "Parabola"));

        when(nodeMapper.selectOne(any())).thenReturn(node);
        when(practiceService.start(1L, 10L, 55L, null, 3)).thenReturn(card);

        ToolExecutionResult result = tool.executeForGovernor(
                "call-1",
                new ToolContext(1L, 10L),
                objectMapper.readTree("{\"nodeCode\":\"KT12\",\"size\":3}"));

        JsonNode json = objectMapper.readTree(result.content());
        assertThat(result.practiceCard()).isSameAs(card);
        assertThat(json.path("sessionId").asLong()).isEqualTo(901L);
        assertThat(json.path("questionCount").asInt()).isEqualTo(1);
        assertThat(json).hasSize(2);
        assertThat(json.has("node")).isFalse();
        assertThat(json.has("questions")).isFalse();
        assertThat(result.content()).doesNotContain("What shape is the graph?");
        assertThat(result.content()).doesNotContain("Parabola");

        verify(practiceService).start(1L, 10L, 55L, null, 3);
    }

    @Test
    void missingOrUnknownNodeThrowsToolException() {
        assertThatThrownBy(() -> tool.execute(new ToolContext(1L, 10L), objectMapper.createObjectNode()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("nodeCode is required");

        when(nodeMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT99")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("nodeCode")
                .hasMessageContaining("KT99");

        verifyNoInteractions(practiceService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaDisallowsUnknownArguments() {
        var function = (java.util.Map<String, Object>) tool.schema().get("function");
        var parameters = (java.util.Map<String, Object>) function.get("parameters");

        assertThat(parameters).containsEntry("additionalProperties", false);
    }

    @Test
    void sizeDefaultsToTwo() throws Exception {
        KgNode node = node(55L, 10L, "KT12", "Quadratic Functions");
        PracticeStartVO card = practiceCard(node, 901L, question("Q1", "A"), question("Q2", "B"));

        when(nodeMapper.selectOne(any())).thenReturn(node);
        when(practiceService.start(1L, 10L, 55L, null, 2)).thenReturn(card);

        tool.execute(new ToolContext(1L, 10L), objectMapper.createObjectNode().put("nodeCode", "KT12"));

        verify(practiceService).start(1L, 10L, 55L, null, 2);
    }

    @Test
    void sizeClampsToLowerBound() throws Exception {
        KgNode node = node(55L, 10L, "KT12", "Quadratic Functions");
        PracticeStartVO card = practiceCard(node, 901L, question("Q1", "A"));

        when(nodeMapper.selectOne(any())).thenReturn(node);
        when(practiceService.start(1L, 10L, 55L, null, 1)).thenReturn(card);

        tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT12").put("size", 0));

        verify(practiceService).start(1L, 10L, 55L, null, 1);
    }

    @Test
    void sizeClampsToUpperBound() throws Exception {
        KgNode node = node(55L, 10L, "KT12", "Quadratic Functions");
        PracticeStartVO card = practiceCard(node, 901L, question("Q1", "A"), question("Q2", "B"), question("Q3", "C"));

        when(nodeMapper.selectOne(any())).thenReturn(node);
        when(practiceService.start(1L, 10L, 55L, null, 3)).thenReturn(card);

        tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("nodeCode", "KT12").put("size", 9));

        verify(practiceService).start(1L, 10L, 55L, null, 3);
    }

    private KgNode node(Long id, Long courseId, String nodeCode, String name) {
        KgNode node = new KgNode();
        node.setId(id);
        node.setCourseId(courseId);
        node.setNodeCode(nodeCode);
        node.setName(name);
        return node;
    }

    private PracticeStartVO practiceCard(KgNode node, Long sessionId, PaperQuestionVO... questions) {
        PracticeStartVO card = new PracticeStartVO();
        card.setSessionId(sessionId);
        PracticeStartVO.NodeRef nodeRef = new PracticeStartVO.NodeRef();
        nodeRef.setNodeId(node.getId());
        nodeRef.setNodeCode(node.getNodeCode());
        nodeRef.setName(node.getName());
        card.setNode(nodeRef);
        card.setQuestions(List.of(questions));
        return card;
    }

    private PaperQuestionVO question(String stem, String optionText) {
        PaperQuestionVO question = new PaperQuestionVO();
        question.setQuestionId(1L);
        question.setStem(stem);
        question.setType(1);

        PaperQuestionVO.OptionVO option = new PaperQuestionVO.OptionVO();
        option.setKey("A");
        option.setText(optionText);
        question.setOptions(List.of(option));
        return question;
    }
}
