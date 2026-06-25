package com.wenjin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenjin.common.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleImageToMarkdownAiClientTest {

    @Test
    void buildBody_containsImageAndInstruction() throws Exception {
        Object body = OpenAiCompatibleImageToMarkdownAiClient.buildBody("glm-5v-turbo", "data:image/jpeg;base64,AAA");
        String json = OpenAiCompatibleImageToMarkdownAiClient.MAPPER.writeValueAsString(body);
        assertThat(json).contains("glm-5v-turbo").contains("image_url")
                .contains("data:image/jpeg;base64,AAA").contains("Markdown");
    }

    @Test
    void parseContent_extractsText() throws Exception {
        JsonNode root = OpenAiCompatibleImageToMarkdownAiClient.MAPPER
                .readTree("{\"choices\":[{\"message\":{\"content\":\"# 标题\\n内容\"}}]}");
        assertThat(OpenAiCompatibleImageToMarkdownAiClient.parseContent(root)).contains("# 标题");
    }

    @Test
    void parseContent_missingChoices_throws() throws Exception {
        JsonNode root = OpenAiCompatibleImageToMarkdownAiClient.MAPPER.readTree("{}");
        assertThatThrownBy(() -> OpenAiCompatibleImageToMarkdownAiClient.parseContent(root))
                .isInstanceOf(BusinessException.class);
    }
}
