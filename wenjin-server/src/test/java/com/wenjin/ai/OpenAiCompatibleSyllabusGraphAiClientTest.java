package com.wenjin.ai;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleSyllabusGraphAiClientTest {

    @Test
    void buildExtractPrompt_containsRulesAndText() {
        String p = OpenAiCompatibleSyllabusGraphAiClient.buildExtractPrompt("第1章 绪论\n1.1 软件危机");
        assertThat(p)
                .contains("知识图谱")
                .contains("『待复核』")
                .contains("第1章 绪论")
                .contains("包含")
                .contains("前置");
    }
}
