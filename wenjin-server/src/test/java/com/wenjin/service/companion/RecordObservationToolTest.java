package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.service.memory.StudentMemoryService;
import com.wenjin.support.MemoryKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordObservationToolTest {

    @Mock
    private StudentMemoryService memoryService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FeatureFlagsProperties flags;
    private RecordObservationTool tool;

    @BeforeEach
    void setUp() {
        flags = new FeatureFlagsProperties();
        flags.setMemoryEnabled(true);
        tool = new RecordObservationTool(memoryService, flags, objectMapper);
    }

    @Test
    void sideEffectingAndSchema() {
        assertThat(tool.name()).isEqualTo("record_observation");
        assertThat(tool.sideEffecting()).isTrue();
        assertThat(tool.params()).isNotEmpty();
    }

    @Test
    void execute_writesWithSalience() throws Exception {
        when(memoryService.upsertOrReinforce(
                eq(1L), eq(10L), eq(MemoryKind.SEMANTIC), eq("偏好图示"),
                isNull(), isNull(), any(BigDecimal.class)))
                .thenReturn(42L);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("content", "偏好图示");
        args.put("kind", "SEMANTIC");
        args.put("salience", 1.2);
        String json = tool.execute(new ToolContext(1L, 10L), args);

        assertThat(json).contains("\"id\":42").contains("recorded");
        verify(memoryService).upsertOrReinforce(
                eq(1L), eq(10L), eq(MemoryKind.SEMANTIC), eq("偏好图示"),
                isNull(), isNull(), any(BigDecimal.class));
    }

    @Test
    void governor_writeBudget_thirdCallBlockedWithoutToolBudget() {
        LimitsProperties limits = new LimitsProperties();
        limits.getMemory().setWritePerRun(2);
        CompanionToolProperties props = new CompanionToolProperties();
        props.setTimeoutMs(5_000);
        props.setMaxResultChars(2_000);

        when(memoryService.upsertOrReinforce(any(), any(), any(Integer.class), any(), any(), any(), any()))
                .thenReturn(1L);

        ToolGovernor governor = new ToolGovernor(List.of(tool), objectMapper, props, limits);
        ToolExecutionState state = new ToolExecutionState(10);
        ToolContext ctx = new ToolContext(1L, 10L);
        String args = "{\"content\":\"obs\",\"kind\":\"SEMANTIC\"}";

        assertThat(governor.execute(ctx, new com.wenjin.ai.CompanionAiClient.ToolCall("c1", "record_observation", args), state).ok())
                .isTrue();
        assertThat(governor.execute(ctx, new com.wenjin.ai.CompanionAiClient.ToolCall("c2", "record_observation", args), state).ok())
                .isTrue();
        ToolExecutionResult blocked = governor.execute(
                ctx, new com.wenjin.ai.CompanionAiClient.ToolCall("c3", "record_observation", args), state);

        assertThat(blocked.ok()).isFalse();
        assertThat(blocked.guarded()).isTrue();
        assertThat(blocked.content()).contains(ToolErrorCodes.TOOL_BUDGET_EXCEEDED);
        assertThat(state.memoryWrites()).isEqualTo(2);
        // only two successful writes consumed tool budget
        assertThat(state.used()).isEqualTo(2);
        verify(memoryService, times(2)).upsertOrReinforce(any(), any(), any(Integer.class), any(), any(), any(), any());
    }

    @Test
    void whenMemoryDisabled_returnsNotAllowed() throws Exception {
        flags.setMemoryEnabled(false);
        ToolExecutionResult result = tool.executeForGovernor(
                "c1", new ToolContext(1L, 10L),
                objectMapper.createObjectNode().put("content", "x").put("kind", "SEMANTIC"));
        assertThat(result.ok()).isFalse();
        assertThat(result.content()).contains(ToolErrorCodes.TOOL_NOT_ALLOWED);
    }
}
