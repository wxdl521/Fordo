package com.wenjin.sysconfig;

import com.wenjin.config.AiProperties;
import com.wenjin.dto.AdminAiTestVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * AiConnectionTester：用当前生效配置发极小请求（MockRestServiceServer 拦截，不打外网）；错误摘要不含 key。
 */
class AiConnectionTesterTest {

    private static final String KEY = "sk-live-abcdef1234567890";
    private static final String VISION_KEY = "zhipu.vision-key-0987654321";
    private static final String OK_BODY = "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}";

    private AiProperties props;
    private MockRestServiceServer server;
    private AiConnectionTester tester;

    @BeforeEach
    void setUp() {
        props = new AiProperties();
        props.setBaseUrl("https://api.deepseek.com/");
        props.setModel("deepseek-v4-pro");
        props.setApiKey(KEY);
        props.getVision().setBaseUrl("https://open.bigmodel.cn/api/paas/v4");
        props.getVision().setModel("glm-5v-turbo");
        props.getVision().setApiKey(VISION_KEY);
        props.getEmbedding().setEnabled(true);
        props.getEmbedding().setApiKey("zhipu.embed-key-1122334455");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tester = new AiConnectionTester(props, builder);
    }

    @Test
    @DisplayName("文本：POST {base}/v1/chat/completions，Bearer 当前 key，max_tokens 很小；200+choices → ok")
    void text_ok() {
        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + KEY))
                .andExpect(jsonPath("$.model").value("deepseek-v4-pro"))
                .andExpect(jsonPath("$.max_tokens").value(AiConnectionTester.MAX_TOKENS))
                .andExpect(jsonPath("$.stream").value(false))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        AdminAiTestVO r = tester.test("text");

        server.verify();
        assertThat(r.isOk()).isTrue();
        assertThat(r.getModel()).isEqualTo("deepseek-v4-pro");
        assertThat(r.getLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(r.getError()).isNull();
    }

    @Test
    @DisplayName("嵌入：POST {embedding.base}/embeddings，body 含 input 与 dimensions；200+data → ok")
    void embedding_ok() {
        server.expect(requestTo("https://open.bigmodel.cn/api/paas/v4/embeddings"))
                .andExpect(header("Authorization", "Bearer zhipu.embed-key-1122334455"))
                .andExpect(jsonPath("$.model").value("embedding-3"))
                .andExpect(jsonPath("$.input[0]").value("ping"))
                .andExpect(jsonPath("$.dimensions").value(1024))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}", MediaType.APPLICATION_JSON));

        AdminAiTestVO r = tester.test("embedding");

        server.verify();
        assertThat(r.isOk()).isTrue();
        assertThat(r.getModel()).isEqualTo("embedding-3");
    }

    @Test
    @DisplayName("嵌入：关闭 / 无 key 时不发请求直接给出原因")
    void embedding_disabledOrNoKey() {
        props.getEmbedding().setEnabled(false);
        assertThat(tester.test("embedding").getError()).contains("嵌入模型已关闭");
        props.getEmbedding().setEnabled(true);
        props.getEmbedding().setApiKey(" ");
        assertThat(tester.test("embedding").getError()).contains("API Key");
    }

    @Test
    @DisplayName("用的是当前生效值：改了 bean（覆盖保存后）立即用新模型 / 新 key")
    void usesCurrentEffectiveConfig() {
        props.setModel("deepseek-chat");
        props.setApiKey("sk-new-key-000000000000");
        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer sk-new-key-000000000000"))
                .andExpect(jsonPath("$.model").value("deepseek-chat"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        assertThat(tester.test("text").getModel()).isEqualTo("deepseek-chat");
        server.verify();
    }

    @Test
    @DisplayName("视觉：POST {vision.base}/chat/completions（不加 /v1），用视觉 key 与模型")
    void vision_ok() {
        server.expect(requestTo("https://open.bigmodel.cn/api/paas/v4/chat/completions"))
                .andExpect(header("Authorization", "Bearer " + VISION_KEY))
                .andExpect(jsonPath("$.model").value("glm-5v-turbo"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        AdminAiTestVO r = tester.test("vision");

        server.verify();
        assertThat(r.isOk()).isTrue();
        assertThat(r.getModel()).isEqualTo("glm-5v-turbo");
    }

    @Test
    @DisplayName("401 且响应体回显了 key：ok=false，错误摘要含状态码但不含 key")
    void unauthorized_errorSanitized() {
        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"Incorrect API key provided: " + KEY
                                + " (Authorization: Bearer " + KEY + ")\"}}"));

        AdminAiTestVO r = tester.test("text");

        assertThat(r.isOk()).isFalse();
        assertThat(r.getError()).contains("HTTP 401").doesNotContain(KEY).doesNotContain("1234567890");
    }

    @Test
    @DisplayName("连接失败：ok=false，错误摘要为连接失败说明")
    void connectionFailure() {
        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andRespond(withException(new IOException("Connection refused")));

        AdminAiTestVO r = tester.test("text");

        assertThat(r.isOk()).isFalse();
        assertThat(r.getError()).contains("连接失败").contains("Connection refused");
    }

    @Test
    @DisplayName("200 但没有 choices：ok=false")
    void missingChoices() {
        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andRespond(withSuccess("{\"object\":\"error\"}", MediaType.APPLICATION_JSON));

        AdminAiTestVO r = tester.test("text");

        assertThat(r.isOk()).isFalse();
        assertThat(r.getError()).contains("choices");
    }

    @Test
    @DisplayName("已关闭 / 未配 key / 缺基址：不发请求，直接给失败原因")
    void notConfigured_noRequest() {
        props.setEnabled(false);
        assertThat(tester.test("text").getError()).contains("已关闭");

        props.setEnabled(true);
        props.setApiKey("  ");
        assertThat(tester.test("text").getError()).contains("API Key");

        props.getVision().setBaseUrl(null);
        AdminAiTestVO vision = tester.test("vision");
        assertThat(vision.isOk()).isFalse();
        assertThat(vision.getError()).contains("基址");

        props.getVision().setEnabled(false);
        assertThat(tester.test("vision").getError()).contains("视觉模型已关闭");

        server.verify();
    }

    @Test
    @DisplayName("sanitize：抹掉 key 原文、Bearer 片段、sk- 形态片段，压成单行并截断")
    void sanitize() {
        String s = AiConnectionTester.sanitize("bad key sk-abc***xyz9 and " + KEY + "\nBearer whatever", KEY);
        assertThat(s).doesNotContain(KEY).doesNotContain("abc***").doesNotContain("whatever")
                .doesNotContain("\n");
        assertThat(AiConnectionTester.sanitize("x".repeat(1000), KEY)).hasSize(AiConnectionTester.MAX_ERROR_CHARS + 1);
        assertThat(AiConnectionTester.sanitize(null, KEY)).isNull();
    }
}
