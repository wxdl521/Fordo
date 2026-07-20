package com.wenjin.service.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalServiceTest {

    private InMemoryFulltextRetriever retriever;
    private RetrievalService service;

    @BeforeEach
    void setUp() {
        retriever = new InMemoryFulltextRetriever()
                .add(new InMemoryFulltextRetriever.IndexedChunk(
                        1L, 101L, "用例图描述参与者与用例的关系", "用例图节点参考",
                        "{\"path\":\"概念\"}", Set.of("SE-3.2")))
                .add(new InMemoryFulltextRetriever.IndexedChunk(
                        1L, 102L, "数据流图强调加工与数据存储", "数据流图节点参考",
                        null, Set.of("SE-3.1")))
                .add(new InMemoryFulltextRetriever.IndexedChunk(
                        2L, 201L, "其他课程的用例图材料不应命中", "外课",
                        null, Set.of("SE-3.2")));
        service = new RetrievalService(retriever);
    }

    @Test
    @DisplayName("节点圈定命中")
    void scopedHit() {
        var result = service.search(1L, List.of("SE-3.2"), "用例图", 5, false);
        assertThat(result.hits()).extracting(RetrievalHit::chunkId).containsExactly(101L);
        assertThat(result.scopeWidened()).isFalse();
    }

    @Test
    @DisplayName("圈内空命中且 allowWiden → 全课程放宽")
    void widenOnMiss() {
        var result = service.search(1L, List.of("SE-9.9"), "数据流图", 5, true);
        assertThat(result.hits()).extracting(RetrievalHit::chunkId).contains(102L);
        assertThat(result.scopeWidened()).isTrue();
    }

    @Test
    @DisplayName("allowWiden=false 时不放宽")
    void noWiden() {
        var result = service.search(1L, List.of("SE-9.9"), "数据流图", 5, false);
        assertThat(result.hits()).isEmpty();
        assertThat(result.scopeWidened()).isFalse();
    }

    @Test
    @DisplayName("空查询返回空")
    void emptyQuery() {
        assertThat(service.search(1L, List.of("SE-3.2"), "  ", 3, true).hits()).isEmpty();
    }

    @Test
    @DisplayName("注入格式含不可信包裹与资料编号")
    void formatBlocks() {
        var result = service.search(1L, List.of("SE-3.2"), "用例", 3, false);
        List<String> blocks = service.formatInjectionBlocks(result);
        assertThat(blocks.get(0)).contains("仅供参考");
        assertThat(blocks.stream().anyMatch(b -> b.contains("【资料 #101"))).isTrue();
        assertThat(service.injectedChunkIds(result)).containsExactly(101L);
    }

    @Test
    @DisplayName("空命中 format 占位")
    void emptyPlaceholder() {
        List<String> blocks = service.formatInjectionBlocks(RetrievalService.RetrievalResult.empty(false));
        assertThat(blocks).containsExactly(RetrievalService.EMPTY_COVERAGE_PLACEHOLDER);
    }
}
