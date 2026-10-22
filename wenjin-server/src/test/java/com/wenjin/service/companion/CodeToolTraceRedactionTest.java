package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.service.CompanionService.CompanionStreamSink;
import com.wenjin.service.code.CodeExecutionService;
import com.wenjin.service.code.CodeExecutionService.CodeRunResult;
import com.wenjin.service.code.CodeExecutionService.Verdict;
import com.wenjin.service.trace.StepRecord;
import com.wenjin.support.AgentStepType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** F4: run_code_check source/stdin never reach the persisted agent-trace TOOL_CALL step. */
class CodeToolTraceRedactionTest {

    private static final String SECRET = "System.out.println(secretMarkerLine);";

    @Test
    @SuppressWarnings("unchecked")
    void toolCallStepInputIsRedactedOtherToolsUntouched() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CodeExecutionService service = Mockito.mock(CodeExecutionService.class);
        when(service.available()).thenReturn(true);
        when(service.run(anyLong(), anyString(), anyString())).thenReturn(
                new CodeRunResult(Verdict.ACCEPTED, "ok", "", 0, null, false, 5L, null));
        ObjectProvider<CodeExecutionService> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        RunCodeCheckTool tool = new RunCodeCheckTool(provider, mapper, new LimitsProperties());
        CompanionTool plain = new CompanionTool() {
            @Override public String name() { return "get_plan_progress"; }
            @Override public Map<String, Object> schema() { return Map.of(); }
            @Override public String execute(ToolContext ctx, JsonNode args) { return "{\"ok\":true}"; }
        };
        ToolGovernor governor = new ToolGovernor(List.of(tool, plain), mapper, new CompanionToolProperties(),
                new LimitsProperties());

        CompanionAiClient ai = Mockito.mock(CompanionAiClient.class);
        when(ai.streamTurn(anyList(), anyList(), any(), any())).thenReturn(
                new CompanionAiClient.StreamTurnResult("好的", List.of(), null, "stop"));

        List<StepRecord> steps = new ArrayList<>();
        CompanionOrchestrator.Shell shell = new CompanionOrchestrator.Shell() {
            @Override public int degrade(CompanionStreamSink sink, String reason) { return 0; }
            @Override public void streamLegacy(CompanionStreamSink sink) { }
            @Override public String fallbackSystemPrompt() { return ""; }
            @Override public void persist(String text) { }
            @Override public boolean cancelRequested() { return false; }
            @Override public void step(StepRecord record) { steps.add(record); }
        };
        CompanionOrchestrator orch = new CompanionOrchestrator(ai, governor, shell);
        CompanionStreamSink sink = new CompanionStreamSink() {
            @Override public void token(String token) { }
        };

        String args = "{\"code\":" + mapper.writeValueAsString(SECRET) + ",\"stdin\":\"s3cret-in\"}";
        orch.run(request(tool, plain, new CompanionAiClient.ToolCall("c1", "run_code_check", args)), sink);

        StepRecord toolStep = steps.stream()
                .filter(s -> AgentStepType.TOOL_CALL.equals(s.stepType())).findFirst().orElseThrow();
        assertThat(toolStep.name()).isEqualTo("run_code_check");
        assertThat(toolStep.inputJson()).doesNotContain("secretMarkerLine").doesNotContain("s3cret-in")
                .contains("<redacted len=");

        // other tools: input recorded verbatim
        steps.clear();
        orch.run(request(tool, plain, new CompanionAiClient.ToolCall("c2", "get_plan_progress", "{\"code\":\"keep\"}")),
                sink);
        StepRecord otherStep = steps.stream()
                .filter(s -> "get_plan_progress".equals(s.name())).findFirst().orElseThrow();
        assertThat(otherStep.inputJson()).isEqualTo("{\"code\":\"keep\"}");
    }

    private static CompanionOrchestrator.Request request(CompanionTool a, CompanionTool b,
                                                         CompanionAiClient.ToolCall forced) {
        return new CompanionOrchestrator.Request(List.of(Map.of("role", "user", "content", "hi")),
                List.of(a, b), new ToolContext(7L, 1L, null, null), forced, 3, 4, 1000, "", null, null);
    }
}
