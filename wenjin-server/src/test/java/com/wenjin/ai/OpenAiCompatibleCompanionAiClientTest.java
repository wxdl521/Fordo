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
    @DisplayName("首块常见的空 content（role 块）返回 null，不误吐")
    void parseRoleChunk() {
        String line = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}";
        assertThat(OpenAiCompatibleCompanionAiClient.parseDeltaContent(line)).isEmpty();
    }
}
