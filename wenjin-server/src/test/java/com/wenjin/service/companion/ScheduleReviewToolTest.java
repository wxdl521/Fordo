package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.KgNodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScheduleReviewToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReviewScheduleStore scheduleStore = new ReviewScheduleStore();
    private final KgNodeMapper nodeMapper = mock(KgNodeMapper.class);

    private ScheduleReviewTool tool;

    @BeforeEach
    void setUp() {
        scheduleStore.clear();
        tool = new ScheduleReviewTool(scheduleStore, new NodeToolSupport(nodeMapper), objectMapper);
    }

    @Test
    void pinsNodeIdempotentlySameDay() throws Exception {
        KgNode node = new KgNode();
        node.setId(5L);
        node.setCourseId(10L);
        node.setNodeCode("KT12");
        node.setName("Queues");
        when(nodeMapper.selectOne(any())).thenReturn(node);

        JsonNode args = objectMapper.readTree("{\"nodeCode\":\"KT12\",\"mode\":\"TODAY\"}");
        JsonNode first = objectMapper.readTree(tool.execute(new ToolContext(1L, 10L), args));
        JsonNode second = objectMapper.readTree(tool.execute(new ToolContext(1L, 10L), args));

        assertThat(first.path("pinned").asBoolean()).isTrue();
        assertThat(first.path("created").asBoolean()).isTrue();
        assertThat(first.path("mode").asText()).isEqualTo("TODAY");
        assertThat(second.path("created").asBoolean()).isFalse();
        assertThat(scheduleStore.forcedToday(1L, 10L)).containsEntry("KT12", ReviewScheduleStore.Mode.TODAY);
    }

    @Test
    void sideEffectingIsTrue() {
        assertThat(tool.sideEffecting()).isTrue();
    }

    @Test
    void unknownNodeThrows() {
        when(nodeMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> tool.execute(
                new ToolContext(1L, 10L),
                objectMapper.readTree("{\"nodeCode\":\"KT99\",\"mode\":\"ADVANCE\"}")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("KT99");
    }
}
