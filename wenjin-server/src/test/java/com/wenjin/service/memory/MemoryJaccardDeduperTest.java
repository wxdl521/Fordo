package com.wenjin.service.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MemoryJaccardDeduperTest {

    private final MemoryJaccardDeduper deduper = new MemoryJaccardDeduper();

    @Test
    @DisplayName("相同内容 Jaccard=1，判为同义")
    void sameContent_isSynonym() {
        String a = "学生在递归章节总是混淆基线条件";
        assertThat(deduper.jaccard(a, a)).isEqualTo(1.0);
        assertThat(deduper.isSynonym(a, a)).isTrue();
    }

    @Test
    @DisplayName("完全不同内容 Jaccard=0，非同义")
    void differentContent_notSynonym() {
        String a = "递归基线条件";
        String b = "Dijkstra 最短路径算法";
        assertThat(deduper.jaccard(a, b)).isEqualTo(0.0);
        assertThat(deduper.isSynonym(a, b)).isFalse();
    }

    @Test
    @DisplayName("边界 0.6：恰好达到阈值判为同义")
    void boundaryPointSix_isSynonym() {
        // A={a,b,c}, B={a,b,d,e,f} → ∩=2 ∪=6 → 2/6≈0.333
        // Construct exact 0.6: ∩=3 ∪=5 → 3/5=0.6
        // A tokens: w1 w2 w3 w4   (4)
        // B tokens: w1 w2 w3 w5 w6 (5) → ∩=3 ∪=6 → 0.5
        // A: t1 t2 t3 (3)
        // B: t1 t2 t3 t4 t5 (5) → ∩=3 ∪=5 → 0.6
        String a = "alpha beta gamma";
        String b = "alpha beta gamma delta epsilon";
        assertThat(deduper.tokenize(a)).containsExactlyInAnyOrder("alpha", "beta", "gamma");
        assertThat(deduper.tokenize(b))
                .containsExactlyInAnyOrder("alpha", "beta", "gamma", "delta", "epsilon");
        assertThat(deduper.jaccard(a, b)).isCloseTo(0.6, within(1e-9));
        assertThat(deduper.isSynonym(a, b, 0.6)).isTrue();
        assertThat(deduper.isSynonym(a, b, 0.6000001)).isFalse();
    }

    @Test
    @DisplayName("中英文混合分词：拉丁词小写 + 汉字单字")
    void mixedTokenization() {
        Set<String> tokens = deduper.tokenize("Hello 递归 HELLO");
        assertThat(tokens).contains("hello", "递", "归");
        assertThat(tokens).hasSize(3);
    }

    @Test
    @DisplayName("空/空白 content 相似度为 0")
    void blank_returnsZero() {
        assertThat(deduper.jaccard("", "abc")).isZero();
        assertThat(deduper.jaccard(null, "abc")).isZero();
        assertThat(deduper.isSynonym("   ", "x")).isFalse();
    }
}
