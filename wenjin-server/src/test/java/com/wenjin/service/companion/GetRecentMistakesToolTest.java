package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.dto.MisconceptionSummary;
import com.wenjin.dto.MistakeReport;
import com.wenjin.dto.MistakeVO;
import com.wenjin.service.MistakeQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetRecentMistakesToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MistakeQueryService mistakeQueryService = mock(MistakeQueryService.class);
    private GetRecentMistakesTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetRecentMistakesTool(mistakeQueryService, objectMapper);
    }

    @Test
    @DisplayName("名称 / 标签 / 只读可缓存 / 计入讲解类")
    void metadata() {
        assertThat(tool.name()).isEqualTo("get_recent_mistakes");
        assertThat(tool.label()).isEqualTo("正在翻看你的错题…");
        assertThat(tool.sideEffecting()).isFalse();
        assertThat(ToolGovernor.READ_CACHEABLE_TOOLS).contains("get_recent_mistakes");
        assertThat(tool.schema()).containsEntry("type", "function");
    }

    @Test
    @DisplayName("默认 limit=3，practiceSessionId 缺省时取 ToolContext 的复盘会话；结果紧凑 JSON")
    void defaultsToContextReviewSession() throws Exception {
        MistakeVO m = new MistakeVO(9L, "题干", 1,
                List.of(new MistakeVO.OptionRef("B", "访谈")),
                List.of(new MistakeVO.OptionRef("A", "用例")),
                List.of(new MistakeVO.NodeRef("SE-2.1", "需求获取")),
                new MistakeVO.NodeRef("SE-3.2", "用例图"),
                "2026-10-20T09:00:00", "PRACTICE", 77L);
        when(mistakeQueryService.report(1L, 10L, null, 77L, 3)).thenReturn(new MistakeReport(
                List.of(m), List.of(new MisconceptionSummary("SE-2.1", "需求获取", 2))));

        JsonNode json = objectMapper.readTree(
                tool.execute(new ToolContext(1L, 10L, "SE-3.2", 77L), objectMapper.createObjectNode()));

        assertThat(json.at("/mistakes/0/questionId").asLong()).isEqualTo(9L);
        assertThat(json.at("/mistakes/0/chosen/0/key").asText()).isEqualTo("B");
        assertThat(json.at("/mistakes/0/correct/0/text").asText()).isEqualTo("用例");
        assertThat(json.at("/mistakes/0/misconceptionNodes/0/nodeCode").asText()).isEqualTo("SE-2.1");
        assertThat(json.at("/topMisconceptions/0/nodeCode").asText()).isEqualTo("SE-2.1");
        assertThat(json.at("/topMisconceptions/0/hits").asInt()).isEqualTo(2);
        assertThat(json.has("note")).isFalse();
    }

    @Test
    @DisplayName("显式参数优先于上下文；focus 节点不会被当作过滤条件")
    void explicitArgsWin() throws Exception {
        when(mistakeQueryService.report(any(), any(), any(), any(), anyInt())).thenReturn(MistakeReport.empty());
        ObjectNode args = objectMapper.createObjectNode()
                .put("nodeCode", "SE-2.1")
                .put("practiceSessionId", 5)
                .put("limit", 5);

        tool.execute(new ToolContext(1L, 10L, "SE-3.2", 77L), args);

        verify(mistakeQueryService).report(1L, 10L, "SE-2.1", 5L, 5);
    }

    @Test
    @DisplayName("无错题 → {mistakes:[], note:近期没有错题}")
    void emptyResultHasNote() throws Exception {
        when(mistakeQueryService.report(eq(1L), eq(10L), isNull(), isNull(), eq(3)))
                .thenReturn(MistakeReport.empty());

        JsonNode json = objectMapper.readTree(tool.execute(new ToolContext(1L, 10L), null));

        assertThat(json.path("mistakes").isArray()).isTrue();
        assertThat(json.path("mistakes")).isEmpty();
        assertThat(json.path("note").asText()).isEqualTo("近期没有错题");
        assertThat(json.has("topMisconceptions")).isFalse();
    }

    @Test
    @DisplayName("governor 参数校验：limit 越界 / practiceSessionId 非正 / nodeCode 非法 → 拦截，不执行查询")
    void governorRejectsBadArgs() {
        CompanionToolProperties props = new CompanionToolProperties();
        props.setEnabled(true);
        props.setMaxRounds(3);
        props.setMaxToolCalls(5);
        ToolGovernor governor = new ToolGovernor(List.of(tool), objectMapper, props);

        for (String bad : List.of("{\"limit\":6}", "{\"limit\":0}", "{\"practiceSessionId\":0}",
                "{\"nodeCode\":\"DROP TABLE;\"}", "{\"unknown\":1}")) {
            ToolExecutionResult result = governor.execute(new ToolContext(1L, 10L),
                    new CompanionAiClient.ToolCall("c1", "get_recent_mistakes", bad), new ToolExecutionState(5));
            assertThat(result.ok()).as(bad).isFalse();
        }
        verify(mistakeQueryService, never()).report(any(), any(), any(), any(), anyInt());
    }
}
