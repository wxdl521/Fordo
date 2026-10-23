package com.wenjin.service.companion;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.service.CompanionService.CompanionStreamSink;
import com.wenjin.service.trace.StepRecord;
import com.wenjin.support.AgentStepType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** 强制（非模型）工具调用不得回放 assistant.tool_calls：DeepSeek 思考模式要求其带 reasoning_content，合成的会 400。 */
class CompanionOrchestratorForcedCallTest {

    private static final CompanionAiClient.ToolCall FORCED =
            new CompanionAiClient.ToolCall("intent-start-practice", "start_practice", "{\"nodeCode\":\"KT01\"}");

    @Test
    @SuppressWarnings("unchecked")
    void forcedCallSendsPlainSystemContextAndRetriesWithFallbackPromptOnFailure() {
        CompanionTool tool = new CompanionTool() {
            @Override public String name() { return "start_practice"; }
            @Override public Map<String, Object> schema() { return Map.of("type", "function"); }
            @Override public String execute(ToolContext ctx, com.fasterxml.jackson.databind.JsonNode args) { return "{}"; }
        };
        ToolGovernor governor = Mockito.mock(ToolGovernor.class);
        when(governor.execute(any(), any(), any(), any())).thenReturn(
                new ToolExecutionResult("intent-start-practice", "start_practice", true, "{\"sessionId\":9}"));
        CompanionAiClient ai = Mockito.mock(CompanionAiClient.class);
        when(ai.streamTurn(anyList(), anyList(), any(), any()))
                .thenThrow(new RuntimeException("boom"))
                .thenAnswer(inv -> {
                    ((java.util.function.Consumer<String>) inv.getArgument(3)).accept("练习好了");
                    return new CompanionAiClient.StreamTurnResult("练习好了", List.of(), null, "stop");
                });

        List<StepRecord> steps = new ArrayList<>();
        List<String> persisted = new ArrayList<>();
        CompanionOrchestrator.Shell shell = new CompanionOrchestrator.Shell() {
            @Override public int degrade(CompanionStreamSink sink, String reason) { return 9; }
            @Override public void streamLegacy(CompanionStreamSink sink) { throw new AssertionError("legacy"); }
            @Override public String fallbackSystemPrompt() { return "FALLBACK"; }
            @Override public void persist(String text) { persisted.add(text); }
            @Override public boolean cancelRequested() { return false; }
            @Override public void step(StepRecord record) { steps.add(record); }
        };
        CompanionOrchestrator orch = new CompanionOrchestrator(ai, governor, shell);
        CompanionOrchestrator.Request req = new CompanionOrchestrator.Request(
                List.of(Map.of("role", "system", "content", "SYS"), Map.of("role", "user", "content", "出题")),
                List.of(tool), new ToolContext(7L, 1L, null, null), FORCED, 3, 4, 1000, "", null, null);

        CompanionOrchestrator.Outcome out = orch.run(req, new CompanionStreamSink() {
            @Override public void token(String token) { }
        });

        assertThat(out.toolsCalled()).containsExactly("start_practice");
        assertThat(persisted).containsExactly("练习好了");
        assertThat(steps).anyMatch(s -> AgentStepType.TOOL_CALL.equals(s.stepType()));

        ArgumentCaptor<List<Map<String, Object>>> sent = ArgumentCaptor.forClass(List.class);
        Mockito.verify(ai, Mockito.times(2)).streamTurn(sent.capture(), anyList(), any(), any());
        for (List<Map<String, Object>> messages : sent.getAllValues()) {
            assertThat(messages).noneMatch(m -> m.containsKey("tool_calls"));
            assertThat(messages).noneMatch(m -> "tool".equals(m.get("role")));
            assertThat(messages.get(messages.size() - 1)).containsEntry("role", "system");
            assertThat((String) messages.get(messages.size() - 1).get("content"))
                    .contains("系统已代学生调用 start_practice").contains("{\"sessionId\":9}");
        }
        // 失败后重试沿用 fallback 系统提示，而不是退回 legacy 流式
        assertThat(sent.getAllValues().get(1).get(0)).containsEntry("content", "FALLBACK");
    }
}
