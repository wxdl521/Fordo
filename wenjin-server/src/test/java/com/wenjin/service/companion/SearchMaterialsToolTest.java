package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.service.rag.InMemoryFulltextRetriever;
import com.wenjin.service.rag.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class SearchMaterialsToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FeatureFlagsProperties flags;
    private SearchMaterialsTool tool;

    @BeforeEach
    void setUp() {
        flags = new FeatureFlagsProperties();
        flags.setRagEnabled(true);
        InMemoryFulltextRetriever retriever = new InMemoryFulltextRetriever()
                .add(new InMemoryFulltextRetriever.IndexedChunk(
                        10L, 55L, "用例图参与者与系统边界要点", "用例图",
                        null, Set.of("SE-3.2")));
        tool = new SearchMaterialsTool(new RetrievalService(retriever), flags, new LimitsProperties(), objectMapper);
    }

    @Test
    void schemaAndName() {
        assertThat(tool.name()).isEqualTo("search_materials");
        assertThat(tool.sideEffecting()).isFalse();
        assertThat(ToolGovernor.READ_CACHEABLE_TOOLS).contains("search_materials");
    }

    @Test
    void execute_returnsSnippets() throws Exception {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "用例图");
        ArrayNode codes = args.putArray("nodeCodes");
        codes.add("SE-3.2");
        String json = tool.execute(new ToolContext(1L, 10L), args);
        assertThat(json).contains("\"chunkId\":55").contains("用例图");
    }

    @Test
    void executeForGovernor_whenRagDisabled_returnsNotAllowed() throws Exception {
        flags.setRagEnabled(false);
        ToolExecutionResult result = tool.executeForGovernor(
                "c1", new ToolContext(1L, 10L), objectMapper.createObjectNode().put("query", "x"));
        assertThat(result.ok()).isFalse();
        assertThat(result.guarded()).isTrue();
        assertThat(result.content()).contains(ToolErrorCodes.TOOL_NOT_ALLOWED);
    }

    @Test
    void nodeCodesOverFive_rejected() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "x");
        ArrayNode codes = args.putArray("nodeCodes");
        for (int i = 0; i < 6; i++) {
            codes.add("SE-" + i);
        }
        assertThatThrownBy(() -> tool.execute(new ToolContext(1L, 10L), args))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("max size 5");
    }
}
