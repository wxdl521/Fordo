package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.CompanionToolProperties;
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
        ToolGovernor governor = new ToolGovernor(List.of(), objectMapper, properties(5_000, 5));
        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "missing_tool", "{}"),
                new ToolExecutionState(3));

        assertThat(result.ok()).isFalse();
        assertThat(objectMapper.readTree(result.content()).path("error").asText()).isEqualTo("unknown_tool");
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
        ToolGovernor governor = new ToolGovernor(List.of(), objectMapper, properties(5_000, 2_000));

        assertThatThrownBy(() -> governor.execute(new ToolContext(1L, 10L), null, new ToolExecutionState(3)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> governor.execute(new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "missing_tool", "{}"),
                null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void unknownToolReturnsJsonErrorWithoutThrowing() throws Exception {
        ToolGovernor governor = new ToolGovernor(List.of(), objectMapper, properties(5_000, 2_000));
        ToolExecutionState state = new ToolExecutionState(3);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "missing_tool", "{}"),
                state);

        assertThat(result.toolCallId()).isEqualTo("call_1");
        assertThat(result.toolName()).isEqualTo("missing_tool");
        assertThat(result.ok()).isFalse();
        assertThat(errorCode(result)).isEqualTo("unknown_tool");
        assertThat(state.used()).isZero();
    }

    @Test
    void invalidArgumentsJsonReturnsErrorAndDoesNotExecuteTool() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = new ToolGovernor(
                List.of(new FakeTool("echo", executions, "{\"ok\":true}")),
                objectMapper,
                properties(5_000, 2_000));
        ToolExecutionState state = new ToolExecutionState(3);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "echo", "{bad"),
                state);

        assertThat(result.ok()).isFalse();
        assertThat(errorCode(result)).isEqualTo("invalid_arguments");
        assertThat(executions).hasValue(0);
        assertThat(state.used()).isZero();
    }

    @Test
    void nonObjectArgumentsReturnErrorAndDoNotExecuteTool() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = new ToolGovernor(
                List.of(new StrictTool("start_practice", executions, "{\"ok\":true}")),
                objectMapper,
                properties(5_000, 2_000));
        ToolExecutionState state = new ToolExecutionState(3);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "start_practice", "[]"),
                state);

        assertThat(result.ok()).isFalse();
        assertThat(errorCode(result)).isEqualTo("invalid_arguments");
        assertThat(executions).hasValue(0);
        assertThat(state.used()).isZero();
    }

    @Test
    void unknownArgumentsReturnErrorAndDoNotExecuteTool() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = new ToolGovernor(
                List.of(new StrictTool("start_practice", executions, "{\"ok\":true}")),
                objectMapper,
                properties(5_000, 2_000));
        ToolExecutionState state = new ToolExecutionState(3);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "start_practice",
                        "{\"nodeCode\":\"KT12\",\"extra\":true}"),
                state);

        assertThat(result.ok()).isFalse();
        assertThat(errorCode(result)).isEqualTo("invalid_arguments");
        assertThat(executions).hasValue(0);
        assertThat(state.used()).isZero();
    }

    @Test
    void argumentTypeMismatchReturnsErrorAndDoesNotExecuteTool() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = new ToolGovernor(
                List.of(new StrictTool("start_practice", executions, "{\"ok\":true}")),
                objectMapper,
                properties(5_000, 2_000));
        ToolExecutionState state = new ToolExecutionState(3);

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "start_practice",
                        "{\"nodeCode\":\"KT12\",\"size\":\"abc\"}"),
                state);

        assertThat(result.ok()).isFalse();
        assertThat(errorCode(result)).isEqualTo("invalid_arguments");
        assertThat(executions).hasValue(0);
        assertThat(state.used()).isZero();
    }

    @Test
    void toolCallBudgetBlocksFourthCall() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernor governor = new ToolGovernor(
                List.of(new FakeTool("echo", executions, "{\"ok\":true}")),
                objectMapper,
                properties(5_000, 2_000));
        ToolExecutionState state = new ToolExecutionState(3);
        ToolContext ctx = new ToolContext(1L, 10L);

        assertThat(governor.execute(ctx, new CompanionAiClient.ToolCall("call_1", "echo", "{}"), state).ok()).isTrue();
        assertThat(governor.execute(ctx, new CompanionAiClient.ToolCall("call_2", "echo", "{}"), state).ok()).isTrue();
        assertThat(governor.execute(ctx, new CompanionAiClient.ToolCall("call_3", "echo", "{}"), state).ok()).isTrue();
        ToolExecutionResult blocked = governor.execute(
                ctx,
                new CompanionAiClient.ToolCall("call_4", "echo", "{}"),
                state);

        assertThat(blocked.ok()).isFalse();
        assertThat(errorCode(blocked)).isEqualTo("tool_budget_exceeded");
        assertThat(executions).hasValue(3);
        assertThat(state.used()).isEqualTo(3);
    }

    @Test
    void longSuccessfulJsonResultRemainsValidJsonAndFitsLimit() throws Exception {
        ToolGovernor governor = new ToolGovernor(
                List.of(new FakeTool("echo", new AtomicInteger(),
                        "{\"items\":["
                                + "{\"nodeCode\":\"KT01\",\"name\":\"Loops\"},"
                                + "{\"nodeCode\":\"KT02\",\"name\":\"Arrays\"},"
                                + "{\"nodeCode\":\"KT03\",\"name\":\"Recursion\"}"
                                + "]}")),
                objectMapper,
                properties(5_000, 90));

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "echo", "{}"),
                new ToolExecutionState(3));

        JsonNode json = objectMapper.readTree(result.content());

        assertThat(result.ok()).isTrue();
        assertThat(result.content()).hasSizeLessThanOrEqualTo(90);
        assertThat(json.path("truncated").asBoolean()).isTrue();
        assertThat(json.path("items").isArray()).isTrue();
        assertThat(json.path("items")).isNotEmpty();
        assertThat(json.path("items")).hasSizeLessThan(3);
        assertThat(json.at("/items/0/nodeCode").asText()).isEqualTo("KT01");
    }

    @Test
    void tinySuccessfulObjectLimitReturnsPromptlyInsteadOfLoopingForever() {
        ToolGovernor governor = new ToolGovernor(
                List.of(new FakeTool("echo", new AtomicInteger(), "{\"nodeCode\":\"A\",\"name\":\"B\"}")),
                objectMapper,
                properties(5_000, 1));

        ToolExecutionResult result = assertTimeoutPreemptively(
                Duration.ofMillis(500),
                () -> governor.execute(
                        new ToolContext(1L, 10L),
                        new CompanionAiClient.ToolCall("call_1", "echo", "{}"),
                        new ToolExecutionState(3)));

        assertThat(result.ok()).isTrue();
        assertThat(result.content()).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void zeroMaxResultCharsFallsBackToEmptyStringWithinLimit() {
        ToolGovernor governor = new ToolGovernor(
                List.of(new FakeTool("echo", new AtomicInteger(), "{\"nodeCode\":\"A\",\"name\":\"B\"}")),
                objectMapper,
                properties(5_000, 0));

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
    void timedOutToolReturnsJsonError() throws Exception {
        ToolGovernor governor = new ToolGovernor(
                List.of(new SlowTool("slow")),
                objectMapper,
                properties(50, 2_000));
        long started = System.nanoTime();

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "slow", "{}"),
                new ToolExecutionState(3));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(result.ok()).isFalse();
        assertThat(errorCode(result)).isEqualTo("tool_timeout");
        assertThat(elapsedMs).isLessThan(800);
    }

    @Test
    void executePreservesPracticeCardSideChannelFromToolOutput() {
        PracticeStartVO card = new PracticeStartVO();
        card.setSessionId(901L);
        ToolGovernor governor = new ToolGovernor(
                List.of(new PracticeCardTool("start_practice", card)),
                objectMapper,
                properties(5_000, 2_000));

        ToolExecutionResult result = governor.execute(
                new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("call_1", "start_practice", "{}"),
                new ToolExecutionState(3));

        assertThat(result.ok()).isTrue();
        assertThat(result.practiceCard()).isSameAs(card);
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
        return objectMapper.readTree(result.content()).path("error").asText();
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
                                            "size", Map.of("type", "integer")),
                                    "required", List.of("nodeCode"))));
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
}
