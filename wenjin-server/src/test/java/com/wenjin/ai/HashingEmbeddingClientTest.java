package com.wenjin.ai;

import com.wenjin.service.rag.EmbeddingCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HashingEmbeddingClientTest {

    private final HashingEmbeddingClient client = new HashingEmbeddingClient(256);

    @Test
    void deterministic_dimensioned_normalized() {
        List<float[]> a = client.embed(List.of("栈是后进先出的线性表", "队列"));
        List<float[]> b = new HashingEmbeddingClient(256).embed(List.of("栈是后进先出的线性表", "队列"));
        assertThat(a).hasSize(2);
        assertThat(a.get(0)).hasSize(256).containsExactly(b.get(0));
        double norm = 0;
        for (float x : a.get(0)) {
            norm += x * x;
        }
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void similarTextScoresHigherThanUnrelated() {
        float[] q = client.embed(List.of("栈的后进先出特性")).get(0);
        float[] near = client.embed(List.of("栈具有后进先出的特性")).get(0);
        float[] far = client.embed(List.of("数据库事务的隔离级别")).get(0);
        assertThat(EmbeddingCodec.cosine(q, near)).isGreaterThan(EmbeddingCodec.cosine(q, far) + 0.2);
    }

    @Test
    void emptyTextIsZeroVector_andMetadata() {
        assertThat(client.embed(List.of("")).get(0)).containsOnly(0f);
        assertThat(client.modelId()).isEqualTo("hash-ngram@256");
        assertThat(client.available()).isTrue();
    }
}
