package com.wenjin.service.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RrfFusionTest {

    private static RetrievalHit hit(long id, String content) {
        return new RetrievalHit(id, content, "src", "{}", 9.9);
    }

    @Test
    @DisplayName("score = Σ 1/(k+rank)：两路都命中的块排第一，分数精确")
    void fusedScoresExact() {
        List<RetrievalHit> ft = List.of(hit(1, "a"), hit(2, "b"), hit(3, "c"));
        List<RetrievalHit> vec = List.of(hit(3, "c-vec"), hit(4, "d"), hit(1, "a-vec"));

        List<RetrievalHit> out = RrfFusion.fuse(List.of(ft, vec), 60, 10);

        assertThat(out).extracting(RetrievalHit::chunkId).containsExactly(1L, 3L, 2L, 4L);
        assertThat(out.get(0).score()).isCloseTo(1.0 / 61 + 1.0 / 63, within(1e-12));
        assertThat(out.get(1).score()).isCloseTo(1.0 / 63 + 1.0 / 61, within(1e-12));
        assertThat(out.get(2).score()).isCloseTo(1.0 / 62, within(1e-12));
        assertThat(out.get(3).score()).isCloseTo(1.0 / 62, within(1e-12));
        // 正文取首次出现（全文优先）
        assertThat(out.get(1).content()).isEqualTo("c");
    }

    @Test
    @DisplayName("同分：最好名次 → 首次出现列表（全文在前）→ chunkId 升序；截断到 topK")
    void tieBreakAndTopK() {
        List<RetrievalHit> ft = List.of(hit(9, "x"));
        List<RetrievalHit> vec = List.of(hit(5, "y"));
        assertThat(RrfFusion.fuse(List.of(ft, vec), 60, 5)).extracting(RetrievalHit::chunkId).containsExactly(9L, 5L);
        assertThat(RrfFusion.fuse(List.of(ft, vec), 60, 1)).extracting(RetrievalHit::chunkId).containsExactly(9L);
    }

    @Test
    @DisplayName("单列表融合保序；同列表重复 chunk 只计首个名次；空 / topK<=0 → 空")
    void singleListAndDuplicates() {
        List<RetrievalHit> ft = List.of(hit(1, "a"), hit(1, "a2"), hit(2, "b"));
        List<RetrievalHit> out = RrfFusion.fuse(List.of(ft), 60, 5);
        assertThat(out).extracting(RetrievalHit::chunkId).containsExactly(1L, 2L);
        assertThat(out.get(1).score()).isCloseTo(1.0 / 62, within(1e-12));
        assertThat(RrfFusion.fuse(List.of(), 60, 5)).isEmpty();
        assertThat(RrfFusion.fuse(List.of(ft), 60, 0)).isEmpty();
    }
}
