package com.wenjin.service.rag;

import com.wenjin.ai.EmbeddingClient;
import com.wenjin.ai.HashingEmbeddingClient;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveEmbeddingResolverTest {

    private FeatureFlagsProperties flags;
    private LimitsProperties limits;
    private StubClient real;

    @BeforeEach
    void setUp() {
        flags = new FeatureFlagsProperties();
        limits = new LimitsProperties();
        real = new StubClient();
    }

    private ActiveEmbeddingResolver resolver() {
        return new ActiveEmbeddingResolver(flags, limits, real);
    }

    @Test
    @DisplayName("真实客户端 available → 选它")
    void realWhenAvailable() {
        real.available = true;
        assertThat(resolver().resolve()).containsSame(real);
        assertThat(resolver().vectorModeOn()).isTrue();
    }

    @Test
    @DisplayName("真实不可用 + allow-hashing → HashingEmbeddingClient(1024)，同一实例复用")
    void hashingFallback() {
        limits.getRag().setAllowHashingEmbedding(true);
        ActiveEmbeddingResolver r = resolver();
        EmbeddingClient c = r.resolve().orElseThrow();
        assertThat(c).isInstanceOf(HashingEmbeddingClient.class);
        assertThat(c.modelId()).isEqualTo("hash-ngram@" + ActiveEmbeddingResolver.HASHING_DIM);
        assertThat(r.resolve()).containsSame(c);
    }

    @Test
    @DisplayName("真实不可用 + 禁止哈希（生产）→ 向量模式关")
    void offWhenHashingDisallowed() {
        limits.getRag().setAllowHashingEmbedding(false);
        assertThat(resolver().resolve()).isEmpty();
        assertThat(resolver().vectorModeOn()).isFalse();
    }

    @Test
    @DisplayName("开关矩阵：rag-vector 关 或 rag 关 → 向量模式关（即使真实客户端可用）")
    void flagsMatrix() {
        real.available = true;
        flags.setRagVectorEnabled(false);
        assertThat(resolver().resolve()).isEmpty();
        flags.setRagVectorEnabled(true);
        flags.setRagEnabled(false);
        assertThat(resolver().resolve()).isEmpty();
    }

    @Test
    @DisplayName("无真实客户端 bean（null）时按哈希规则")
    void nullReal() {
        limits.getRag().setAllowHashingEmbedding(true);
        ActiveEmbeddingResolver r = new ActiveEmbeddingResolver(flags, limits, null);
        assertThat(r.resolve()).isPresent();
        limits.getRag().setAllowHashingEmbedding(false);
        assertThat(r.resolve()).isEmpty();
    }

    static final class StubClient implements EmbeddingClient {
        boolean available;

        @Override
        public List<float[]> embed(List<String> texts) {
            return texts.stream().map(t -> new float[]{1f}).toList();
        }

        @Override
        public String modelId() {
            return "stub@1";
        }

        @Override
        public boolean available() {
            return available;
        }
    }
}
