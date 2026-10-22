package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.CodeToolProperties;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.service.code.CodeExecutionService;
import com.wenjin.service.code.CodeExecutionService.CaseResult;
import com.wenjin.service.code.CodeExecutionService.CodeJudgeResult;
import com.wenjin.service.code.CodeExecutionService.CodeRunResult;
import com.wenjin.service.code.CodeExecutionService.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunCodeCheckToolTest {

    private static final String SECRET_LINE = "System.out.println(secretMarkerLine);";
    private static final String SOURCE = "public class Main {\n  public static void main(String[] a) {\n    "
            + SECRET_LINE + "\n  }\n}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final CodeExecutionService service = Mockito.mock(CodeExecutionService.class);
    private final CodeToolProperties props = new CodeToolProperties();
    private RunCodeCheckTool tool;

    @BeforeEach
    void setUp() {
        when(service.available()).thenReturn(true);
        tool = new RunCodeCheckTool(providerOf(service), mapper, props);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CodeExecutionService> providerOf(CodeExecutionService s) {
        ObjectProvider<CodeExecutionService> p = Mockito.mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(s);
        return p;
    }

    private JsonNode args(String json) throws Exception {
        return mapper.readTree(json);
    }

    private JsonNode argsWithCode(String extra) throws Exception {
        var node = mapper.createObjectNode();
        node.put("code", SOURCE);
        if (extra != null) {
            node.setAll((com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(extra));
        }
        return node;
    }

    private static CodeRunResult run(Verdict v, String stdout, String stderr, String compile) {
        return new CodeRunResult(v, stdout, stderr, v == Verdict.ACCEPTED ? 0 : 1, compile, false, 15L, null);
    }

    // ── schema / params ──

    @Test
    void schemaIsStrictAndRequiresCode() {
        assertThat(tool.name()).isEqualTo("run_code_check");
        assertThat(tool.sideEffecting()).isTrue();
        @SuppressWarnings("unchecked")
        var fn = (java.util.Map<String, Object>) tool.schema().get("function");
        @SuppressWarnings("unchecked")
        var params = (java.util.Map<String, Object>) fn.get("parameters");
        assertThat(params.get("required")).isEqualTo(List.of("code"));
        assertThat(params.get("additionalProperties")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        var properties = (java.util.Map<String, Object>) params.get("properties");
        assertThat(properties.keySet()).containsExactly("code", "stdin", "questionId");
    }

    @Test
    void paramValidationRejectsMissingOversizedAndBadTypes() throws Exception {
        var specs = tool.params();
        assertThat(ToolParamValidator.validate(specs, args("{}"))).contains("code");
        assertThat(ToolParamValidator.validate(specs, args("{\"code\":\"x\",\"stdin\":\"" + "a".repeat(2001) + "\"}")))
                .contains("stdin");
        assertThat(ToolParamValidator.validate(specs, args("{\"code\":\"" + "a".repeat(10001) + "\"}")))
                .contains("code");
        assertThat(ToolParamValidator.validate(specs, args("{\"code\":\"x\",\"questionId\":0}"))).contains("questionId");
        assertThat(ToolParamValidator.validate(specs, args("{\"code\":\"x\",\"questionId\":\"7\"}"))).contains("questionId");
        assertThat(ToolParamValidator.validate(specs, args("{\"code\":\"x\",\"stdin\":\"1 2\",\"questionId\":7}"))).isNull();
    }

    // ── availability / kill switch ──

    @Test
    void availableFollowsServiceAndAbsenceMeansUnavailable() {
        assertThat(tool.available()).isTrue();
        when(service.available()).thenReturn(false);
        assertThat(tool.available()).isFalse();
        assertThat(new RunCodeCheckTool(providerOf(null), mapper, props).available()).isFalse();
    }

    @Test
    void executeWhenUnavailableIsGuardedNotAllowedAndNeverRuns() throws Exception {
        when(service.available()).thenReturn(false);
        ToolExecutionResult r = tool.executeForGovernor("c1", new ToolContext(7L, 1L), argsWithCode(null));

        assertThat(r.ok()).isFalse();
        assertThat(r.guarded()).isTrue();
        assertThat(r.codeCard()).isNull();
        assertThat(mapper.readTree(r.content()).path("code").asText()).isEqualTo(ToolErrorCodes.TOOL_NOT_ALLOWED);
        verify(service, never()).run(anyLong(), anyString(), anyString());
        verify(service, never()).judge(anyLong(), anyLong(), anyString());
    }

    // ── RUN mode ──

    @Test
    void runModeSummarisesCompileErrorHeadAndNeverEchoesSource() throws Exception {
        String compile = "Main.java:3: error: ';' expected\n  int x\n       ^\n"
                + "l4\nl5\nl6\nl7\nl8\nl9-should-be-cut\nl10";
        when(service.run(eq(7L), eq(SOURCE), eq(""))).thenReturn(run(Verdict.COMPILE_ERROR, "", "", compile));

        ToolExecutionResult r = tool.executeForGovernor("c1", new ToolContext(7L, 1L), argsWithCode(null));

        assertThat(r.ok()).isTrue();
        JsonNode body = mapper.readTree(r.content());
        assertThat(body.path("mode").asText()).isEqualTo("RUN");
        assertThat(body.path("verdict").asText()).isEqualTo("COMPILE_ERROR");
        assertThat(body.path("compileError").asText()).startsWith("Main.java:3: error").doesNotContain("l9-should-be-cut");
        assertThat(r.content()).doesNotContain("secretMarkerLine").doesNotContain("public class Main");
        // card: structured, source-free
        assertThat(r.codeCard().mode()).isEqualTo("RUN");
        assertThat(r.codeCard().verdict()).isEqualTo("COMPILE_ERROR");
        assertThat(r.codeCard().passedCases()).isNull();
        assertThat(mapper.writeValueAsString(r.codeCard())).doesNotContain("secretMarkerLine");
    }

    @Test
    void runModePassesStdinAndClipsStdoutTo300() throws Exception {
        when(service.run(eq(7L), eq(SOURCE), eq("1 2"))).thenReturn(run(Verdict.ACCEPTED, "x".repeat(500), "", null));

        ToolExecutionResult r = tool.executeForGovernor("c1", new ToolContext(7L, 1L),
                argsWithCode("{\"stdin\":\"1 2\"}"));

        String stdout = mapper.readTree(r.content()).path("stdout").asText();
        assertThat(stdout).hasSize(301).endsWith("…");
        assertThat(r.content().length()).isLessThanOrEqualTo(2000);
    }

    // ── JUDGE mode ──

    @Test
    void judgeModeListsOnlyVisibleFailuresAndCountsHiddenOnes() throws Exception {
        List<CaseResult> cases = List.of(
                new CaseResult(1, true, false, Verdict.ACCEPTED, "1", "2", "2", 5),
                new CaseResult(2, false, false, Verdict.WRONG_ANSWER, "3", "6", "5", 5),
                new CaseResult(3, false, true, Verdict.WRONG_ANSWER, null, null, null, 5));
        when(service.judge(eq(7L), eq(42L), eq(SOURCE))).thenReturn(
                new CodeJudgeResult(Verdict.WRONG_ANSWER, 1, 3, null, cases, 20L, null));

        ToolExecutionResult r = tool.executeForGovernor("c1", new ToolContext(7L, 1L),
                argsWithCode("{\"questionId\":42}"));

        JsonNode body = mapper.readTree(r.content());
        assertThat(body.path("mode").asText()).isEqualTo("JUDGE");
        assertThat(body.path("passed").asInt()).isEqualTo(1);
        assertThat(body.path("total").asInt()).isEqualTo(3);
        assertThat(body.path("failures")).hasSize(1);
        assertThat(body.path("failures").get(0).path("case").asInt()).isEqualTo(2);
        assertThat(body.path("failures").get(0).path("expected").asText()).isEqualTo("6");
        assertThat(body.path("failures").get(0).path("actual").asText()).isEqualTo("5");
        assertThat(body.path("hiddenFailed").asInt()).isEqualTo(1);
        verify(service, never()).run(anyLong(), anyString(), anyString());
        // card carries the (already redacted) per-case results
        assertThat(r.codeCard().mode()).isEqualTo("JUDGE");
        assertThat(r.codeCard().passedCases()).isEqualTo(1);
        assertThat(r.codeCard().totalCases()).isEqualTo(3);
        assertThat(r.codeCard().cases()).hasSize(3);
        assertThat(r.codeCard().cases().get(2).expectedPreview()).isNull();
    }

    @Test
    void rejectedAndRateLimitedStillReturnAMessageAndCard() throws Exception {
        when(service.run(anyLong(), anyString(), anyString())).thenReturn(
                new CodeRunResult(Verdict.RATE_LIMITED, "", "", null, null, false, 0, "请求太频繁，稍后再试"));

        ToolExecutionResult r = tool.executeForGovernor("c1", new ToolContext(7L, 1L), argsWithCode(null));

        assertThat(r.ok()).isTrue();
        assertThat(mapper.readTree(r.content()).path("verdict").asText()).isEqualTo("RATE_LIMITED");
        assertThat(mapper.readTree(r.content()).path("message").asText()).contains("稍后");
        assertThat(r.codeCard().message()).contains("稍后");
    }

    @Test
    void worstCaseSummaryStaysValidJsonUnderTheGovernorLimit() throws Exception {
        List<CaseResult> cases = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            cases.add(new CaseResult(i, false, false, Verdict.WRONG_ANSWER, "\"".repeat(100), "\"".repeat(100),
                    "\"".repeat(100), 1));
        }
        when(service.judge(anyLong(), anyLong(), anyString())).thenReturn(
                new CodeJudgeResult(Verdict.WRONG_ANSWER, 0, 10, null, cases, 1L, "m".repeat(300)));
        LimitsProperties limits = new LimitsProperties();
        ToolGovernor governor = new ToolGovernor(List.of(tool), mapper, new CompanionToolProperties(), limits);

        ToolExecutionResult r = governor.execute(new ToolContext(7L, 1L),
                new CompanionAiClient.ToolCall("c1", "run_code_check",
                        "{\"code\":\"class Main{}\",\"questionId\":9}"),
                new ToolExecutionState(4));

        assertThat(r.ok()).isTrue();
        assertThat(r.content()).doesNotContain("[已截断]");
        assertThat(mapper.readTree(r.content()).path("verdict").asText()).isEqualTo("WRONG_ANSWER");
        assertThat(r.codeCard()).isNotNull();
    }

    // ── governor integration: per-run cap, bad args, budget ──

    @Test
    void governorAllowsOneCallPerRunThenGuardsWithoutRunningAgain() throws Exception {
        when(service.run(anyLong(), anyString(), anyString())).thenReturn(run(Verdict.ACCEPTED, "ok", "", null));
        ToolGovernor governor = new ToolGovernor(List.of(tool), mapper, new CompanionToolProperties(),
                new LimitsProperties());
        ToolExecutionState state = new ToolExecutionState(6);
        var call = new CompanionAiClient.ToolCall("c1", "run_code_check", "{\"code\":\"class Main{}\"}");

        ToolExecutionResult first = governor.execute(new ToolContext(7L, 1L), call, state);
        ToolExecutionResult second = governor.execute(new ToolContext(7L, 1L), call, state);

        assertThat(first.ok()).isTrue();
        assertThat(first.codeCard()).isNotNull();
        assertThat(second.ok()).isFalse();
        assertThat(second.guarded()).isTrue();
        assertThat(second.codeCard()).isNull();
        assertThat(mapper.readTree(second.content()).path("code").asText())
                .isEqualTo(ToolErrorCodes.TOOL_BUDGET_EXCEEDED);
        verify(service, Mockito.times(1)).run(anyLong(), anyString(), anyString());
        assertThat(state.used()).isEqualTo(1);
    }

    @Test
    void governorRejectsBadArgsWithoutConsumingCapOrBudget() {
        ToolGovernor governor = new ToolGovernor(List.of(tool), mapper, new CompanionToolProperties(),
                new LimitsProperties());
        ToolExecutionState state = new ToolExecutionState(6);

        ToolExecutionResult bad = governor.execute(new ToolContext(7L, 1L),
                new CompanionAiClient.ToolCall("c1", "run_code_check", "{\"stdin\":\"1\"}"), state);

        assertThat(bad.ok()).isFalse();
        assertThat(state.used()).isZero();
        assertThat(state.toolCapUsed("run_code_check")).isZero();
        verify(service, never()).run(anyLong(), anyString(), anyString());
    }

    @Test
    void governorHonoursConfiguredCap() {
        when(service.run(anyLong(), anyString(), anyString())).thenReturn(run(Verdict.ACCEPTED, "ok", "", null));
        ToolGovernor governor = new ToolGovernor(List.of(tool), mapper, new CompanionToolProperties(),
                new LimitsProperties());
        CodeToolProperties two = new CodeToolProperties();
        two.setMaxPerRun(2);
        governor.setCodeToolProperties(two);
        ToolExecutionState state = new ToolExecutionState(6);
        var call = new CompanionAiClient.ToolCall("c1", "run_code_check", "{\"code\":\"class Main{}\"}");

        assertThat(governor.execute(new ToolContext(7L, 1L), call, state).ok()).isTrue();
        assertThat(governor.execute(new ToolContext(7L, 1L), call, state).ok()).isTrue();
        assertThat(governor.execute(new ToolContext(7L, 1L), call, state).ok()).isFalse();
    }
}
