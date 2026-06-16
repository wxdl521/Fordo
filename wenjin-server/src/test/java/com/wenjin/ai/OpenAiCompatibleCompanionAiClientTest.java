package com.wenjin.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SSE 增量解析纯函数单测（不触网）。 */
class OpenAiCompatibleCompanionAiClientTest {

    @Test
    @DisplayName("正常 data 行取出 delta.content")
    void parseNormal() {
        String line = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}";
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(line)).isEqualTo("你好");
    }

    @Test
    @DisplayName("[DONE] / 空 data / 注释行 / 缺 content 均返回 null")
    void parseEdges() {
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent("data: [DONE]")).isNull();
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent("data: ")).isNull();
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(": keep-alive")).isNull();
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent("")).isNull();
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(
                "data: {\"choices\":[{\"delta\":{}}]}")).isNull();
    }

    @Test
    @DisplayName("首块 role+空content 返回空串")
    void parseRoleChunk() {
        String line = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}";
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(line)).isEmpty();
    }

    @Test
    @DisplayName("畸形 JSON 返回 null")
    void parseMalformedJson() {
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent("data: {\"choices\":[{\"delta\":"))
                .isNull();
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent("data: not-json-at-all"))
                .isNull();
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent("data: {\"choices\":\"not-array\"}"))
                .isNull();
    }

    @Test
    @DisplayName("空 choices 数组返回 null")
    void parseEmptyChoices() {
        String line = "data: {\"choices\":[]}";
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(line)).isNull();
    }

    @Test
    @DisplayName("区分 null / missing / empty string content")
    void parseContentVariants() {
        // content 为 null（JSON null）
        String nullContent = "data: {\"choices\":[{\"delta\":{\"content\":null}}]}";
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(nullContent)).isNull();

        // content 字段不存在（missing）
        String missingContent = "data: {\"choices\":[{\"delta\":{}}]}";
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(missingContent)).isNull();

        // content 为空串 ""（已在 parseRoleChunk 测过，这里再验证单独场景）
        String emptyContent = "data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}";
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(emptyContent)).isEmpty();
    }

}
