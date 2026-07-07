package com.wenjin.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SSE delta parsing and companion request body tests. */
class OpenAiCompatibleCompanionAiClientTest {

    @Test
    @DisplayName("parseDeltaContent returns content for a normal data line")
    void parseNormal() {
        String line = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}";
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(line)).isEqualTo("hello");
    }

    @Test
    @DisplayName("parseDeltaContent returns null for control and empty lines")
    void parseEdges() {
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent("data: [DONE]")).isNull();
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent("data: ")).isNull();
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(": keep-alive")).isNull();
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent("")).isNull();
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(
                "data: {\"choices\":[{\"delta\":{}}]}")).isNull();
    }

    @Test
    @DisplayName("parseDeltaContent returns empty string for an empty assistant chunk")
    void parseRoleChunk() {
        String line = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}";
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(line)).isEmpty();
    }

    @Test
    @DisplayName("parseDeltaContent returns null for malformed JSON")
    void parseMalformedJson() {
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent("data: {\"choices\":[{\"delta\":"))
                .isNull();
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent("data: not-json-at-all"))
                .isNull();
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent("data: {\"choices\":\"not-array\"}"))
                .isNull();
    }

    @Test
    @DisplayName("parseDeltaContent returns null for empty choices")
    void parseEmptyChoices() {
        String line = "data: {\"choices\":[]}";
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(line)).isNull();
    }

    @Test
    @DisplayName("parseDeltaContent handles null, missing, and empty content")
    void parseContentVariants() {
        String nullContent = "data: {\"choices\":[{\"delta\":{\"content\":null}}]}";
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(nullContent)).isNull();

        String missingContent = "data: {\"choices\":[{\"delta\":{}}]}";
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(missingContent)).isNull();

        String emptyContent = "data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}";
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(emptyContent)).isEmpty();
    }

    @Test
    @DisplayName("parseTurnOutcome parses assistant tool_calls")
    void parseTurnOutcomeToolCalls() {
        String json = """
                {"choices":[{"message":{"role":"assistant","tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"query_mastery","arguments":"{\\"nodeCode\\":\\"KT01\\"}"}}
                ]}}]}
                """;
        CompanionAiClient.TurnOutcome outcome = OpenAiCompatibleCompanionAiClient.parseTurnOutcome(json);
        assertThat(outcome).isInstanceOf(CompanionAiClient.ToolCalls.class);
        CompanionAiClient.ToolCalls calls = (CompanionAiClient.ToolCalls) outcome;
        assertThat(calls.calls()).extracting(CompanionAiClient.ToolCall::id).containsExactly("call_1");
        assertThat(calls.calls()).extracting(CompanionAiClient.ToolCall::name).containsExactly("query_mastery");
        assertThat(calls.calls()).extracting(CompanionAiClient.ToolCall::argumentsJson).containsExactly("{\"nodeCode\":\"KT01\"}");
    }

    @Test
    @DisplayName("parseTurnOutcome parses final assistant text")
    void parseTurnOutcomeFinalText() {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"continue\"}}]}";
        assertThat(OpenAiCompatibleCompanionAiClient.parseTurnOutcome(json))
                .isEqualTo(new CompanionAiClient.FinalText("continue"));
    }

    @Test
    @DisplayName("parseTurnOutcome falls back to empty final text for unusable responses")
    void parseTurnOutcomeEmptyFinalTextFallbacks() {
        assertThat(OpenAiCompatibleCompanionAiClient.parseTurnOutcome("data"))
                .isEqualTo(new CompanionAiClient.FinalText(""));
        assertThat(OpenAiCompatibleCompanionAiClient.parseTurnOutcome("{bad"))
                .isEqualTo(new CompanionAiClient.FinalText(""));
        assertThat(OpenAiCompatibleCompanionAiClient.parseTurnOutcome("{\"choices\":[]}"))
                .isEqualTo(new CompanionAiClient.FinalText(""));
        assertThat(OpenAiCompatibleCompanionAiClient.parseTurnOutcome("{\"choices\":[{}]}"))
                .isEqualTo(new CompanionAiClient.FinalText(""));
    }

    @Test
    @DisplayName("parseTurnOutcome defaults missing or null tool arguments to empty JSON")
    void parseTurnOutcomeToolCallArgumentFallbacks() {
        String missingArguments = """
                {"choices":[{"message":{"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"query_mastery"}}
                ]}}]}
                """;
        String nullArguments = """
                {"choices":[{"message":{"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"query_mastery","arguments":null}}
                ]}}]}
                """;

        CompanionAiClient.ToolCalls missing = (CompanionAiClient.ToolCalls) OpenAiCompatibleCompanionAiClient.parseTurnOutcome(missingArguments);
        CompanionAiClient.ToolCalls nullValue = (CompanionAiClient.ToolCalls) OpenAiCompatibleCompanionAiClient.parseTurnOutcome(nullArguments);

        assertThat(missing.calls()).extracting(CompanionAiClient.ToolCall::argumentsJson).containsExactly("{}");
        assertThat(nullValue.calls()).extracting(CompanionAiClient.ToolCall::argumentsJson).containsExactly("{}");
    }

    @Test
    @DisplayName("buildToolRequestBody omits tools for null or empty schemas")
    void buildToolRequestBodyOmitToolFields() {
        Map<String, Object> nullSchemas = OpenAiCompatibleCompanionAiClient.buildToolRequestBody(
                "demo-model", 0.2, null, null);
        Map<String, Object> emptySchemas = OpenAiCompatibleCompanionAiClient.buildToolRequestBody(
                "demo-model", 0.2, null, List.of());

        assertThat(nullSchemas).containsEntry("model", "demo-model");
        assertThat(nullSchemas).containsEntry("temperature", 0.2);
        assertThat(nullSchemas).containsEntry("stream", false);
        assertThat(nullSchemas).containsEntry("messages", List.of());
        assertThat(nullSchemas).doesNotContainKeys("tools", "tool_choice");

        assertThat(emptySchemas).containsEntry("messages", List.of());
        assertThat(emptySchemas).doesNotContainKeys("tools", "tool_choice");
    }

    @Test
    @DisplayName("buildToolRequestBody includes tools and stream false when schemas exist")
    void buildToolRequestBodyIncludesTools() {
        Map<String, Object> schema = Map.of("type", "function", "function", Map.of("name", "lookup"));

        Map<String, Object> body = OpenAiCompatibleCompanionAiClient.buildToolRequestBody(
                "demo-model", 0.9, List.of(Map.of("role", "user", "content", "hi")), List.of(schema));

        assertThat(body).containsEntry("model", "demo-model");
        assertThat(body).containsEntry("temperature", 0.9);
        assertThat(body).containsEntry("stream", false);
        assertThat(body).containsEntry("tool_choice", "auto");
        assertThat(body.get("messages")).isEqualTo(List.of(Map.of("role", "user", "content", "hi")));
        assertThat(body.get("tools")).isEqualTo(List.of(schema));
    }

    @Test
    @DisplayName("buildStreamRequestBody posts stream true")
    void buildStreamRequestBodySetsStreamFlag() {
        Map<String, Object> body = OpenAiCompatibleCompanionAiClient.buildStreamRequestBody(
                "demo-model", 0.3, null);

        assertThat(body).containsEntry("model", "demo-model");
        assertThat(body).containsEntry("temperature", 0.3);
        assertThat(body).containsEntry("stream", true);
        assertThat(body).containsEntry("messages", List.of());
    }
}
