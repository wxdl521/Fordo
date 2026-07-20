package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.entity.StudentMemory;
import com.wenjin.service.memory.StudentMemoryService;
import com.wenjin.support.MemoryKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecallStudentContextToolTest {

    @Mock
    private StudentMemoryService memoryService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FeatureFlagsProperties flags;
    private RecallStudentContextTool tool;

    @BeforeEach
    void setUp() {
        flags = new FeatureFlagsProperties();
        flags.setMemoryEnabled(true);
        tool = new RecallStudentContextTool(memoryService, flags, objectMapper);
    }

    @Test
    void schemaAndName() {
        assertThat(tool.name()).isEqualTo("recall_student_context");
        assertThat(tool.sideEffecting()).isFalse();
        assertThat(tool.schema().get("type")).isEqualTo("function");
    }

    @Test
    void execute_searchesSelfOnlyWithDefaults() throws Exception {
        StudentMemory m = new StudentMemory();
        m.setId(9L);
        m.setKind(MemoryKind.SEMANTIC);
        m.setContent("偏好代码");
        when(memoryService.searchAndReinforce(eq(1L), eq(10L), eq("代码"), isNull(), eq(5)))
                .thenReturn(List.of(m));

        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "代码");
        String json = tool.execute(new ToolContext(1L, 10L), args);

        assertThat(json).contains("偏好代码").contains("SEMANTIC");
        verify(memoryService).searchAndReinforce(1L, 10L, "代码", null, 5);
    }

    @Test
    void executeForGovernor_whenMemoryDisabled_returnsNotAllowed() throws Exception {
        flags.setMemoryEnabled(false);
        ToolExecutionResult result = tool.executeForGovernor(
                "c1", new ToolContext(1L, 10L), objectMapper.createObjectNode().put("query", "x"));

        assertThat(result.ok()).isFalse();
        assertThat(result.guarded()).isTrue();
        assertThat(result.content()).contains(ToolErrorCodes.TOOL_NOT_ALLOWED);
    }
}
