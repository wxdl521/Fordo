package com.wenjin.ai;

import com.wenjin.common.BusinessException;
import com.wenjin.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleEmbeddingClientTest {

    private static final String URL = "https://open.bigmodel.cn/api/paas/v4/embeddings";

    private AiProperties props;
    private MockRestServiceServer server;
    private OpenAiCompatibleEmbeddingClient client;

    @BeforeEach
    void setUp() {
        props = new AiProperties();
        AiProperties.Embedding e = props.getEmbedding();
        e.setEnabled(true);
        e.setApiKey("k-embed-123");
        e.setDimension(3);
        e.setBatchSize(2);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiCompatibleEmbeddingClient(props, builder);
    }

    private static String resp(int... indexes) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < indexes.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(indexes[i]).append(",\"embedding\":[")
                    .append(indexes[i]).append(".0,0.5,0.25]}");
        }
        return sb.append("]}").toString();
    }

    @Test
    void request_body_and_batching_preserve_order() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer k-embed-123"))
                .andExpect(jsonPath("$.model").value("embedding-3"))
                .andExpect(jsonPath("$.dimensions").value(3))
                .andExpect(jsonPath("$.input.length()").value(2))
                .andExpect(jsonPath("$.input[0]").value("a"))
                // 乱序返回 → 按 index 还原
                .andRespond(withSuccess(resp(1, 0), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.input.length()").value(1))
                .andExpect(jsonPath("$.input[0]").value("c"))
                .andRespond(withSuccess(resp(0), MediaType.APPLICATION_JSON));

        List<float[]> out = client.embed(List.of("a", "b", "c"));

        server.verify();
        assertThat(out).hasSize(3);
        assertThat(out.get(0)[0]).isEqualTo(0f);
        assertThat(out.get(1)[0]).isEqualTo(1f);
        assertThat(out.get(2)).containsExactly(0f, 0.5f, 0.25f);
    }

    @Test
    void gatewayOctetStreamContentType_stillParsed() {
        server.expect(requestTo(URL)).andRespond(withSuccess(resp(0), MediaType.APPLICATION_OCTET_STREAM));
        assertThat(client.embed(List.of("a"))).hasSize(1);
    }

    @Test
    void wrongDimension_throwsAiError() {
        props.getEmbedding().setDimension(4);
        server.expect(requestTo(URL)).andRespond(withSuccess(resp(0), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.embed(List.of("a")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("维度");
    }

    @Test
    void wrongCount_orBadStructure_throws() {
        server.expect(requestTo(URL)).andRespond(withSuccess(resp(0), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.embed(List.of("a", "b"))).isInstanceOf(BusinessException.class);
        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.embed(List.of("a"))).isInstanceOf(BusinessException.class);
    }

    @Test
    void availability_and_modelId_followLiveConfig() {
        assertThat(client.available()).isTrue();
        assertThat(client.modelId()).isEqualTo("embedding-3@3");
        props.getEmbedding().setApiKey(" ");
        assertThat(client.available()).isFalse();
        props.getEmbedding().setApiKey("x");
        props.getEmbedding().setEnabled(false);
        assertThat(client.available()).isFalse();
        assertThatThrownBy(() -> client.embed(List.of("a"))).isInstanceOf(BusinessException.class);
        assertThat(client.embed(new ArrayList<>())).isEmpty();
    }
}
