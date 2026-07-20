package com.wenjin.support;

import com.wenjin.ai.CompanionAiClient.FinalText;
import com.wenjin.ai.CompanionAiClient.ToolCalls;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class Phase1DecisionParserTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "hello plain text"})
    @DisplayName("missing / non-JSON → intent CHAT (old golden compatible)")
    void missingOrPlain_defaultsChat(String raw) {
        Phase1Decision d = Phase1DecisionParser.parse(raw);
        assertThat(d.intent()).isEqualTo(CompanionIntent.CHAT);
        assertThat(d.outcome()).isInstanceOf(FinalText.class);
    }

    @Test
    @DisplayName("final_text without intent → CHAT")
    void finalText_noIntent_chat() {
        Phase1Decision d = Phase1DecisionParser.parse(
                "{\"type\":\"final_text\",\"text\":\"hi\"}");
        assertThat(d.intent()).isEqualTo(CompanionIntent.CHAT);
        assertThat(d.outcome()).isInstanceOf(FinalText.class);
        assertThat(((FinalText) d.outcome()).text()).isEqualTo("hi");
    }

    @Test
    @DisplayName("tool_calls without intent → CHAT + tools")
    void toolCalls_noIntent_chat() {
        Phase1Decision d = Phase1DecisionParser.parse(
                "{\"type\":\"tool_calls\",\"calls\":[{\"id\":\"c1\",\"name\":\"query_mastery\","
                        + "\"arguments\":\"{\\\"nodeCode\\\":\\\"SE-3.2\\\"}\"}]}");
        assertThat(d.intent()).isEqualTo(CompanionIntent.CHAT);
        assertThat(d.outcome()).isInstanceOf(ToolCalls.class);
        assertThat(Phase1DecisionParser.hasToolCalls(d)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "EXPLAIN, EXPLAIN",
            "QUERY, QUERY",
            "PRACTICE, PRACTICE",
            "CHAT, CHAT",
            "explain, EXPLAIN",
            "unknown_xyz, CHAT"
    })
    @DisplayName("intent field is parsed case-insensitively; unknown → CHAT")
    void intentField(String rawIntent, CompanionIntent expected) {
        String json = "{\"type\":\"final_text\",\"text\":\"x\",\"intent\":\"" + rawIntent + "\"}";
        Phase1Decision d = Phase1DecisionParser.parse(json);
        assertThat(d.intent()).isEqualTo(expected);
    }

    @Test
    @DisplayName("EXPLAIN + tool_calls preserved together")
    void explainWithTools() {
        Phase1Decision d = Phase1DecisionParser.parse(
                "{\"type\":\"tool_calls\",\"intent\":\"EXPLAIN\","
                        + "\"calls\":[{\"id\":\"c1\",\"name\":\"get_node_detail\","
                        + "\"arguments\":\"{\\\"nodeCode\\\":\\\"SE-1.1\\\"}\"}]}");
        assertThat(d.intent()).isEqualTo(CompanionIntent.EXPLAIN);
        assertThat(Phase1DecisionParser.isExplain(d)).isTrue();
        assertThat(Phase1DecisionParser.hasToolCalls(d)).isTrue();
        ToolCalls tools = (ToolCalls) d.outcome();
        assertThat(tools.calls()).hasSize(1);
        assertThat(tools.calls().get(0).name()).isEqualTo("get_node_detail");
    }

    @Test
    @DisplayName("null intent JSON node → CHAT")
    void nullIntentField() {
        Phase1Decision d = Phase1DecisionParser.parse(
                "{\"type\":\"final_text\",\"text\":\"x\",\"intent\":null}");
        assertThat(d.intent()).isEqualTo(CompanionIntent.CHAT);
    }

    @Test
    @DisplayName("CompanionIntent.fromRaw edge cases")
    void fromRawEdges() {
        assertThat(CompanionIntent.fromRaw(null)).isEqualTo(CompanionIntent.CHAT);
        assertThat(CompanionIntent.fromRaw("")).isEqualTo(CompanionIntent.CHAT);
        assertThat(CompanionIntent.fromRaw("  practice ")).isEqualTo(CompanionIntent.PRACTICE);
    }
}
