package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.service.trace.TraceContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolGovernorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void errorResultRemainsValidJsonWhenMaxResultCharsIsTiny() throws Exception {
        ToolGovernor governor = newGovernor(List.of(), 5_000, 5);
        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "missing_tool", "{}"),
                new ToolExecutionState(3));

        assertThat(result.ok()).isFalse();
        assertThat(objectMapper.readTree(result.content()).path("code").asText())
                .isEqualTo(ToolErrorCodes.TOOL_NOT_ALLOWED);
        assertThat(result.content().length()).isGreaterThan(5);
    }

    @Test
    void constructorRequiresObjectMapperAndProperties() {
        assertThatThrownBy(() -> new ToolGovernor(List.of(), null, properties(5_000, 2_000)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ToolGovernor(List.of(), objectMapper, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void executeRequiresCallAndState() {
        ToolGovernor governor = newGovernor(List.of(), 5_000, 2_000);

        assertThatThrownBy(() -> governor.execute(new ToolContext(1L, 10L), null, new ToolExecutionState(3)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> governor.execute(new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "missing_tool", "{}"),
                null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void unknownToolReturnsJsonErrorWithoutThrowing() throws Exception {
        ToolGovernor governor = newGovernor(List.of(), 5_000, 2_000);
        ToolExecutionState state = new ToolExecutionState(3);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "missing_tool", "{}"),
                state);

        assertThat(result.toolCallId()).isEqualTo("call_1");
        assertThat(result.toolName()).isEqualTo("missing_tool");
        assertThat(result.ok()).isFalse();
        assertThat(result.guarded()).isTrue();
        assertThat(errorCode(result)).isEqualTo(ToolErrorCodes.TOOL_NOT_ALLOWED);
        assertThat(errorRetryable(result)).isFalse();
        assertThat(state.used()).isEqualTo(1);
    }

    @Test
    void budgetExceededIsGuardedIntercept() throws Exception {
        ToolGovernor governor = newGovernor(
                List.of(new FakeTool("echo", new AtomicInteger(), "{\"ok\":true}")),
                5_000, 2_000);
        // max=2 预留 1 给 side-effect → 只读预算 1
        ToolExecutionState state = new ToolExecutionState(2);
        ToolContext ctx = new ToolContext(1L, 10L);

        assertThat(governor.execute(
                ctx,
                new CompanionAiClient.ToolCall("call_1", "echo", "{}"),
                state).ok()).isTrue();

        ToolExecutionResult blocked = governor.execute(
                ctx,
                new CompanionAiClient.ToolCall("call_2", "echo", "{}"),
                state);

        assertThat(blocked.ok()).isFalse();
        assertThat(blocked.guarded()).isTrue();
        assertThat(errorCode(blocked)).isEqualTo(ToolErrorCodes.TOOL_BUDGET_EXCEEDED);
        assertThat(errorRetryable(blocked)).isFalse();
    }

    @Test
    void successfulExecutionIsNotGuarded() {
        ToolGovernor governor = newGovernor(
                List.of(new FakeTool("echo", new AtomicInteger(), "{\"ok\":true}")),
                5_000, 2_000);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "echo", "{}"),
                new ToolExecutionState(3));

        assertThat(result.ok()).isTrue();
        assertThat(result.guarded()).isFalse();
        assertThat(result.cached()).isFalse();
    }

    @Test
    void toolInternalErrorIsNotGuarded() throws Exception {
        ToolGovernor governor = newGovernor(
                List.of(new FailingTool("boom")),
                5_000, 2_000);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "boom", "{}"),
                new ToolExecutionState(3));

        assertThat(result.ok()).isFalse();
        assertThat(result.guarded()).isFalse();
        assertThat(errorCode(result)).isEqualTo(ToolErrorCodes.TOOL_INTERNAL);
        assertThat(errorRetryable(result)).isTrue();
        // Stack traces never leak into model content
        assertThat(result.content()).doesNotContain("ToolException");
        assertThat(result.content()).doesNotContain("boom from tool body");
    }

    @Test
    void invalidArgumentsJsonReturnsErrorAndDoesNotExecuteToolOrConsumeBudget() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = newGovernor(
                List.of(new FakeTool("echo", executions, "{\"ok\":true}")),
                5_000, 2_000);
        ToolExecutionState state = new ToolExecutionState(3);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "echo", "{bad"),
                state);

        assertThat(result.ok()).isFalse();
        assertThat(result.guarded()).isTrue();
        assertThat(errorCode(result)).isEqualTo(ToolErrorCodes.TOOL_BAD_ARGS);
        assertThat(executions).hasValue(0);
        assertThat(state.used()).isEqualTo(0);
    }

    @Test
    void nonObjectArgumentsReturnErrorAndDoNotExecuteToolOrConsumeBudget() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = newGovernor(
                List.of(new StrictTool("start_practice", executions, "{\"ok\":true}")),
                5_000, 2_000);
        ToolExecutionState state = new ToolExecutionState(3);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "start_practice", "[]"),
                state);

        assertThat(result.ok()).isFalse();
        assertThat(errorCode(result)).isEqualTo(ToolErrorCodes.TOOL_BAD_ARGS);
        assertThat(executions).hasValue(0);
        assertThat(state.used()).isEqualTo(0);
    }

    @Test
    void unknownArgumentsReturnErrorAndDoNotExecuteToolOrConsumeBudget() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = newGovernor(
                List.of(new StrictTool("start_practice", executions, "{\"ok\":true}")),
                5_000, 2_000);
        ToolExecutionState state = new ToolExecutionState(3);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "start_practice",
                        "{\"nodeCode\":\"KT12\",\"extra\":true}"),
                state);

        assertThat(result.ok()).isFalse();
        assertThat(errorCode(result)).isEqualTo(ToolErrorCodes.TOOL_BAD_ARGS);
        assertThat(executions).hasValue(0);
        assertThat(state.used()).isEqualTo(0);
    }

    @Test
    void argumentTypeMismatchReturnsErrorAndDoesNotExecuteToolOrConsumeBudget() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = newGovernor(
                List.of(new StrictTool("start_practice", executions, "{\"ok\":true}")),
                5_000, 2_000);
        ToolExecutionState state = new ToolExecutionState(3);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "start_practice",
                        "{\"nodeCode\":\"KT12\",\"size\":\"abc\"}"),
                state);

        assertThat(result.ok()).isFalse();
        assertThat(errorCode(result)).isEqualTo(ToolErrorCodes.TOOL_BAD_ARGS);
        assertThat(executions).hasValue(0);
        assertThat(state.used()).isEqualTo(0);
    }

    @Test
    void nodeNameArgumentIsAcceptedByStrictSchema() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = newGovernor(
                List.of(new StrictTool("start_practice", executions, "{\"ok\":true}")),
                5_000, 2_000);
        ToolExecutionState state = new ToolExecutionState(3);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "start_practice",
                        "{\"nodeName\":\"Quadratic Functions\",\"size\":2}"),
                state);

        assertThat(result.ok()).isTrue();
        assertThat(executions).hasValue(1);
        assertThat(state.used()).isEqualTo(1);
    }

    @Test
    void missingNodeIdentifierReturnsErrorAndDoesNotExecuteToolOrConsumeBudget() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = newGovernor(
                List.of(new StrictTool("start_practice", executions, "{\"ok\":true}")),
                5_000, 2_000);
        ToolExecutionState state = new ToolExecutionState(3);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "start_practice", "{\"size\":2}"),
                state);

        assertThat(result.ok()).isFalse();
        assertThat(errorCode(result)).isEqualTo(ToolErrorCodes.TOOL_BAD_ARGS);
        assertThat(errorMessage(result)).isEqualTo("Missing required tool argument: one of nodeCode,nodeName");
        assertThat(executions).hasValue(0);
        assertThat(state.used()).isEqualTo(0);
    }

    @Test
    void invalidArgsDoNotConsumeBudgetSoLaterValidCallsStillRun() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = newGovernor(
                List.of(new StrictTool("start_practice", executions, "{\"ok\":true}")),
                5_000, 2_000);
        ToolExecutionState state = new ToolExecutionState(2);
        ToolContext ctx = new ToolContext(1L, 10L);

        ToolExecutionResult unknown = governor.execute(
                ctx,
                new CompanionAiClient.ToolCall("call_1", "missing_tool", "{}"),
                state);
        ToolExecutionResult malformed = governor.execute(
                ctx,
                new CompanionAiClient.ToolCall("call_2", "start_practice", "{bad"),
                state);
        // unknown consumed 1 of max=2; malformed did not consume.
        // start_practice is side-effecting and can use remaining / reserved slot.
        ToolExecutionResult practice = governor.execute(
                ctx,
                new CompanionAiClient.ToolCall("call_3", "start_practice",
                        "{\"nodeName\":\"Quadratic Functions\"}"),
                state);

        assertThat(errorCode(unknown)).isEqualTo(ToolErrorCodes.TOOL_NOT_ALLOWED);
        assertThat(errorCode(malformed)).isEqualTo(ToolErrorCodes.TOOL_BAD_ARGS);
        assertThat(practice.ok()).isTrue();
        assertThat(executions).hasValue(1);
        assertThat(state.used()).isEqualTo(2);
    }

    @Test
    void toolCallBudgetBlocksExtraNonSideEffectingCalls() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = newGovernor(
                List.of(new FakeTool("echo", executions, "{\"ok\":true}")),
                5_000, 2_000);
        // max=3 时预留 1 给 side-effect → 只读最多 2 次
        ToolExecutionState state = new ToolExecutionState(3);
        ToolContext ctx = new ToolContext(1L, 10L);

        assertThat(governor.execute(ctx, new CompanionAiClient.ToolCall("call_1", "echo", "{}"), state).ok()).isTrue();
        assertThat(governor.execute(ctx, new CompanionAiClient.ToolCall("call_2", "echo", "{}"), state).ok()).isTrue();
        ToolExecutionResult blocked = governor.execute(
                ctx,
                new CompanionAiClient.ToolCall("call_3", "echo", "{}"),
                state);

        assertThat(blocked.ok()).isFalse();
        assertThat(errorCode(blocked)).isEqualTo(ToolErrorCodes.TOOL_BUDGET_EXCEEDED);
        assertThat(executions).hasValue(2);
        assertThat(state.used()).isEqualTo(2);
        assertThat(state.nonSideCap()).isEqualTo(2);
    }

    @Test
    void sideEffectingToolUsesReservedBudgetAfterReadOnlyCap() throws Exception {
        AtomicInteger readExec = new AtomicInteger();
        AtomicInteger practiceExec = new AtomicInteger();
        ToolGovernor governor = newGovernor(
                List.of(
                        new FakeTool("echo", readExec, "{\"ok\":true}"),
                        new StrictTool("start_practice", practiceExec, "{\"ok\":true}")),
                5_000, 2_000);
        ToolExecutionState state = new ToolExecutionState(3);
        ToolContext ctx = new ToolContext(1L, 10L);

        assertThat(governor.execute(ctx, new CompanionAiClient.ToolCall("c1", "echo", "{}"), state).ok()).isTrue();
        assertThat(governor.execute(ctx, new CompanionAiClient.ToolCall("c2", "echo", "{}"), state).ok()).isTrue();
        // 第 3 次只读应失败；start_practice 仍可用预留槽
        assertThat(governor.execute(ctx, new CompanionAiClient.ToolCall("c3", "echo", "{}"), state).ok()).isFalse();
        ToolExecutionResult practice = governor.execute(
                ctx,
                new CompanionAiClient.ToolCall("c4", "start_practice",
                        "{\"nodeName\":\"Quadratic Functions\"}"),
                state);

        assertThat(practice.ok()).isTrue();
        assertThat(readExec).hasValue(2);
        assertThat(practiceExec).hasValue(1);
        assertThat(state.used()).isEqualTo(3);
    }

    @Test
    void longSuccessfulResultIsTruncatedWithMarker() {
        String body = "{\"items\":[" + "\"x\"".repeat(50) + "]}";
        // build a long content
        String longContent = "A".repeat(500);
        ToolGovernor governor = newGovernor(
                List.of(new FakeTool("echo", new AtomicInteger(), longContent)),
                5_000, 50);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "echo", "{}"),
                new ToolExecutionState(3));

        assertThat(result.ok()).isTrue();
        assertThat(result.content()).hasSize(50);
        assertThat(result.content()).endsWith("[已截断]");
        assertThat(result.content()).doesNotContain(body);
    }

    @Test
    void zeroMaxResultCharsFallsBackToEmptyStringWithinLimit() {
        ToolGovernor governor = newGovernor(
                List.of(new FakeTool("echo", new AtomicInteger(), "{\"nodeCode\":\"A\",\"name\":\"B\"}")),
                5_000, 0);

        ToolExecutionResult result = assertTimeoutPreemptively(
                Duration.ofMillis(500),
                () -> governor.execute(
                        new ToolContext(1L, 10L),
                        new CompanionAiClient.ToolCall("call_1", "echo", "{}"),
                        new ToolExecutionState(3)));

        assertThat(result.ok()).isTrue();
        assertThat(result.content()).isEmpty();
    }

    @Test
    void timedOutToolReturnsTimeoutEnvelope() throws Exception {
        ToolGovernor governor = newGovernor(
                List.of(new SlowTool("slow")),
                50, 2_000);
        long started = System.nanoTime();

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "slow", "{}"),
                new ToolExecutionState(3));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(result.ok()).isFalse();
        assertThat(result.guarded()).isTrue();
        assertThat(errorCode(result)).isEqualTo(ToolErrorCodes.TOOL_TIMEOUT);
        assertThat(errorRetryable(result)).isTrue();
        assertThat(errorMessage(result)).contains("超时");
        assertThat(elapsedMs).isLessThan(800);
    }

    @Test
    void sideEffectingToolRunsInlineInsteadOfReturningTimeout() {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = newGovernor(
                List.of(new SideEffectingSlowTool("start_practice", executions)),
                10, 2_000);
        ToolExecutionState state = new ToolExecutionState(3);

        ToolExecutionResult result = assertTimeoutPreemptively(
                Duration.ofMillis(500),
                () -> governor.execute(
                        new ToolContext(1L, 10L),
                        new CompanionAiClient.ToolCall("call_1", "start_practice", "{}"),
                        state));

        assertThat(result.ok()).isTrue();
        assertThat(result.content()).isEqualTo("{\"ok\":true}");
        assertThat(executions).hasValue(1);
        assertThat(state.used()).isEqualTo(1);
    }

    @Test
    void executePreservesPracticeCardSideChannelFromToolOutput() {
        PracticeStartVO card = new PracticeStartVO();
        card.setSessionId(901L);
        ToolGovernor governor = newGovernor(
                List.of(new PracticeCardTool("start_practice", card)),
                5_000, 2_000);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "start_practice", "{}"),
                new ToolExecutionState(3));

        assertThat(result.ok()).isTrue();
        assertThat(result.practiceCard()).isSameAs(card);
    }

    @Test
    void cacheableToolSecondCallWithSameArgsIsCachedAndDoesNotConsumeBudgetOrReexecute() {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = newGovernor(
                List.of(new FakeTool("query_mastery", executions, "{\"items\":[]}")),
                5_000, 2_000);
        ToolExecutionState state = new ToolExecutionState(4);
        TraceContext traceCtx = TraceContext.active(7L);
        ToolContext ctx = new ToolContext(1L, 10L);
        String args = "{\"nodeCode\":\"KT12\"}";

        ToolExecutionResult first = governor.execute(
                ctx,
                new CompanionAiClient.ToolCall("call_1", "query_mastery", args),
                state,
                traceCtx);
        ToolExecutionResult second = governor.execute(
                ctx,
                new CompanionAiClient.ToolCall("call_2", "query_mastery", args),
                state,
                traceCtx);

        assertThat(first.ok()).isTrue();
        assertThat(first.cached()).isFalse();
        assertThat(second.ok()).isTrue();
        assertThat(second.cached()).isTrue();
        assertThat(second.content()).isEqualTo(first.content());
        assertThat(executions).hasValue(1);
        assertThat(state.used()).isEqualTo(1);
        assertThat(traceCtx.toolCache()).hasSize(1);
    }

    @Test
    void cacheKeyIgnoresWhitespaceAndKeyOrder() {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = newGovernor(
                List.of(new FakeTool("query_mastery", executions, "{\"items\":[]}")),
                5_000, 2_000);
        ToolExecutionState state = new ToolExecutionState(4);
        TraceContext traceCtx = TraceContext.active(8L);
        ToolContext ctx = new ToolContext(1L, 10L);

        governor.execute(ctx,
                new CompanionAiClient.ToolCall("c1", "query_mastery",
                        "{\"b\":2,\"a\":1}"),
                state, traceCtx);
        ToolExecutionResult second = governor.execute(ctx,
                new CompanionAiClient.ToolCall("c2", "query_mastery",
                        "{ \"a\" : 1 , \"b\" : 2 }"),
                state, traceCtx);

        assertThat(second.cached()).isTrue();
        assertThat(executions).hasValue(1);
        assertThat(state.used()).isEqualTo(1);
    }

    @Test
    void nonCacheableToolIsNotCached() {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = newGovernor(
                List.of(new FakeTool("echo", executions, "{\"ok\":true}")),
                5_000, 2_000);
        ToolExecutionState state = new ToolExecutionState(4);
        TraceContext traceCtx = TraceContext.active(9L);
        ToolContext ctx = new ToolContext(1L, 10L);

        governor.execute(ctx, new CompanionAiClient.ToolCall("c1", "echo", "{}"), state, traceCtx);
        governor.execute(ctx, new CompanionAiClient.ToolCall("c2", "echo", "{}"), state, traceCtx);

        assertThat(executions).hasValue(2);
        assertThat(state.used()).isEqualTo(2);
        assertThat(traceCtx.toolCache()).isEmpty();
    }

    @Test
    void eachToolErrorCodeHasEnvelopeShape() throws Exception {
        // TOOL_NOT_ALLOWED
        ToolExecutionResult notAllowed = newGovernor(List.of(), 5_000, 2_000).execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("c", "nope", "{}"),
                new ToolExecutionState(3));
        assertEnvelope(notAllowed, ToolErrorCodes.TOOL_NOT_ALLOWED, false);

        // TOOL_BUDGET_EXCEEDED
        ToolGovernor g = newGovernor(
                List.of(new FakeTool("echo", new AtomicInteger(), "{}")), 5_000, 2_000);
        ToolExecutionState state = new ToolExecutionState(0);
        ToolExecutionResult budget = g.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("c", "echo", "{}"),
                state);
        assertEnvelope(budget, ToolErrorCodes.TOOL_BUDGET_EXCEEDED, false);

        // TOOL_BAD_ARGS
        ToolExecutionResult badArgs = newGovernor(
                List.of(new FakeTool("echo", new AtomicInteger(), "{}")), 5_000, 2_000)
                .execute(new ToolContext(1L, 10L),
                        new CompanionAiClient.ToolCall("c", "echo", "{bad"),
                        new ToolExecutionState(3));
        assertEnvelope(badArgs, ToolErrorCodes.TOOL_BAD_ARGS, false);

        // TOOL_TIMEOUT
        ToolExecutionResult timeout = newGovernor(List.of(new SlowTool("slow")), 30, 2_000)
                .execute(new ToolContext(1L, 10L),
                        new CompanionAiClient.ToolCall("c", "slow", "{}"),
                        new ToolExecutionState(3));
        assertEnvelope(timeout, ToolErrorCodes.TOOL_TIMEOUT, true);

        // TOOL_INTERNAL
        ToolExecutionResult internal = newGovernor(List.of(new FailingTool("boom")), 5_000, 2_000)
                .execute(new ToolContext(1L, 10L),
                        new CompanionAiClient.ToolCall("c", "boom", "{}"),
                        new ToolExecutionState(3));
        assertEnvelope(internal, ToolErrorCodes.TOOL_INTERNAL, true);
    }

    private void assertEnvelope(ToolExecutionResult result, String code, boolean retryable) throws Exception {
        JsonNode json = objectMapper.readTree(result.content());
        assertThat(json.path("ok").asBoolean()).isFalse();
        assertThat(json.path("code").asText()).isEqualTo(code);
        assertThat(json.path("message").asText()).isNotBlank();
        assertThat(json.path("retryable").asBoolean()).isEqualTo(retryable);
        assertThat(result.guarded()).isEqualTo(!ToolErrorCodes.TOOL_INTERNAL.equals(code));
    }

    private ToolGovernor newGovernor(List<CompanionTool> tools, long timeoutMs, int maxResultChars) {
        LimitsProperties limits = new LimitsProperties();
        limits.getTools().setMaxResultChars(maxResultChars);
        return new ToolGovernor(tools, objectMapper, properties(timeoutMs, maxResultChars), limits);
    }

    private CompanionToolProperties properties(long timeoutMs, int maxResultChars) {
        CompanionToolProperties properties = new CompanionToolProperties();
        properties.setEnabled(true);
        properties.setMaxRounds(2);
        properties.setMaxToolCalls(3);
        properties.setTimeoutMs(timeoutMs);
        properties.setMaxResultChars(maxResultChars);
        return properties;
    }

    private String errorCode(ToolExecutionResult result) throws Exception {
        return objectMapper.readTree(result.content()).path("code").asText();
    }

    private String errorMessage(ToolExecutionResult result) throws Exception {
        return objectMapper.readTree(result.content()).path("message").asText();
    }

    private boolean errorRetryable(ToolExecutionResult result) throws Exception {
        return objectMapper.readTree(result.content()).path("retryable").asBoolean();
    }

    private record FakeTool(String name, AtomicInteger executions, String response) implements CompanionTool {
        @Override
        public Map<String, Object> schema() {
            return Map.of("type", "function", "function", Map.of("name", name));
        }

        @Override
        public String execute(ToolContext ctx, JsonNode args) {
            executions.incrementAndGet();
            return response;
        }
    }

    private record StrictTool(String name, AtomicInteger executions, String response) implements CompanionTool {
        @Override
        public Map<String, Object> schema() {
            return Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", name,
                            "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "nodeCode", Map.of("type", "string"),
                                            "nodeName", Map.of("type", "string"),
                                            "size", Map.of("type", "integer")),
                                    "anyOf", List.of(
                                            Map.of("required", List.of("nodeCode")),
                                            Map.of("required", List.of("nodeName"))))));
        }

        @Override
        public List<ToolParamSpec> params() {
            return List.of(
                    ToolParamSpec.string("nodeCode", false, 32, ToolParamSpec.NODE_CODE_PATTERN),
                    ToolParamSpec.string("nodeName", false, 128, null),
                    ToolParamSpec.integer("size", false, 1.0, 3.0));
        }

        @Override
        public boolean sideEffecting() {
            // 与生产 start_practice 一致：占用副作用预留槽
            return "start_practice".equals(name);
        }

        @Override
        public String execute(ToolContext ctx, JsonNode args) {
            executions.incrementAndGet();
            return response;
        }
    }

    private record PracticeCardTool(String name, PracticeStartVO card) implements CompanionTool {
        @Override
        public Map<String, Object> schema() {
            return Map.of("type", "function", "function", Map.of("name", name));
        }

        @Override
        public String execute(ToolContext ctx, JsonNode args) {
            return "{\"sessionId\":901,\"questionCount\":1}";
        }

        @Override
        public ToolExecutionResult executeForGovernor(String toolCallId, ToolContext ctx, JsonNode args) {
            return new ToolExecutionResult(toolCallId, name, true, execute(ctx, args), card);
        }
    }

    private record SlowTool(String name) implements CompanionTool {
        @Override
        public Map<String, Object> schema() {
            return Map.of("type", "function", "function", Map.of("name", name));
        }

        @Override
        public String execute(ToolContext ctx, JsonNode args) throws ToolException {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolException("interrupted");
            }
            return "{\"ok\":true}";
        }
    }

    private record FailingTool(String name) implements CompanionTool {
        @Override
        public Map<String, Object> schema() {
            return Map.of("type", "function", "function", Map.of("name", name));
        }

        @Override
        public String execute(ToolContext ctx, JsonNode args) throws ToolException {
            throw new ToolException("boom from tool body");
        }
    }

    private record SideEffectingSlowTool(String name, AtomicInteger executions) implements CompanionTool {
        @Override
        public Map<String, Object> schema() {
            return Map.of("type", "function", "function", Map.of("name", name));
        }

        public boolean sideEffecting() {
            return true;
        }

        @Override
        public String execute(ToolContext ctx, JsonNode args) throws ToolException {
            executions.incrementAndGet();
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolException("interrupted");
            }
            return "{\"ok\":true}";
        }
    }
}
