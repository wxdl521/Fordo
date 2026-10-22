package com.wenjin.service.rag;

import com.wenjin.ai.EmbeddingClient;
import com.wenjin.config.LimitsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CourseVectorIndexTest {

    private static CourseVectorIndex index() {
        return new CourseVectorIndex("m@2", List.of(
                new CourseVectorIndex.Entry(1, new float[]{1, 0}, Set.of("N1")),
                new CourseVectorIndex.Entry(2, new float[]{3, 3}, Set.of("N2")),
                new CourseVectorIndex.Entry(3, new float[]{0, 2}, Set.of("N1", "N3")),
                new CourseVectorIndex.Entry(4, new float[]{1, 0, 0}, Set.of("N1")))); // 维度不符，忽略
    }

    @Test
    @DisplayName("余弦排序（向量先归一化）+ minScore 过滤 + limit")
    void cosineRanking() {
        List<CourseVectorIndex.Scored> out = index().search(new float[]{2, 0}, null, 0.0, 10);
        assertThat(out).extracting(CourseVectorIndex.Scored::chunkId).containsExactly(1L, 2L, 3L);
        assertThat(out.get(0).score()).isCloseTo(1.0, within(1e-6));
        assertThat(out.get(1).score()).isCloseTo(Math.sqrt(0.5), within(1e-6));

        assertThat(index().search(new float[]{2, 0}, null, 0.5, 10))
                .extracting(CourseVectorIndex.Scored::chunkId).containsExactly(1L, 2L);
        assertThat(index().search(new float[]{2, 0}, null, 0.0, 1))
                .extracting(CourseVectorIndex.Scored::chunkId).containsExactly(1L);
    }

    @Test
    @DisplayName("节点圈定：块的 APPROVED 节点与范围有交集才参与")
    void nodeScope() {
        assertThat(index().search(new float[]{1, 1}, List.of("N3"), 0.0, 10))
                .extracting(CourseVectorIndex.Scored::chunkId).containsExactly(3L);
        assertThat(index().search(new float[]{1, 1}, List.of("N9"), 0.0, 10)).isEmpty();
        assertThat(new CourseVectorIndex("m", List.of()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("QueryEmbeddingCache：同 model+query 只嵌入一次；换 model 重新嵌入；容量 LRU；0 关闭")
    void queryCache() {
        LimitsProperties limits = new LimitsProperties();
        limits.getRag().setQueryCacheSize(2);
        QueryEmbeddingCache cache = new QueryEmbeddingCache(limits);
        CountingClient a = new CountingClient("a@2");
        CountingClient b = new CountingClient("b@2");

        cache.embedQuery(a, "q1");
        cache.embedQuery(a, "q1");
        assertThat(a.calls.get()).isEqualTo(1);
        cache.embedQuery(b, "q1");
        assertThat(b.calls.get()).isEqualTo(1);
        cache.embedQuery(a, "q2");
        assertThat(cache.size()).isEqualTo(2);
        cache.embedQuery(a, "q1"); // 已被逐出（LRU：a/q1 最早）
        assertThat(a.calls.get()).isEqualTo(3);

        limits.getRag().setQueryCacheSize(0);
        QueryEmbeddingCache off = new QueryEmbeddingCache(limits);
        off.embedQuery(a, "x");
        off.embedQuery(a, "x");
        assertThat(off.size()).isZero();
    }

    static final class CountingClient implements EmbeddingClient {
        final String model;
        final AtomicInteger calls = new AtomicInteger();

        CountingClient(String model) {
            this.model = model;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            calls.incrementAndGet();
            List<float[]> out = new ArrayList<>();
            texts.forEach(t -> out.add(new float[]{1, 0}));
            return out;
        }

        @Override
        public String modelId() {
            return model;
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
