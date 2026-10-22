package com.wenjin.eval.integration;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.eval.fixture.EvalWorld;
import com.wenjin.eval.runner.EvalHarness;
import com.wenjin.service.rag.ActiveEmbeddingResolver;
import com.wenjin.service.rag.FulltextRetriever;
import com.wenjin.service.rag.HybridRetriever;
import com.wenjin.service.rag.RagEmbedService;
import com.wenjin.service.rag.RetrievalHit;
import com.wenjin.service.rag.RetrieverPort;
import com.wenjin.service.rag.VectorIndexCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2 真库验证：RAG_EMBED 作业体（哈希嵌入）写 doc_chunk_embedding、content_sha 幂等跳过，
 * 向量索引 SQL（APPROVED 映射 / 回表 / 覆盖率计数）与混合检索在 MySQL 上可用。
 * <p>评测 profile 默认 rag-vector-enabled=false（回放保持纯全文）；本用例临时开启并在结束时清掉写入的向量。
 * Gated by {@code WENJIN_EVAL_IT=true}。
 */
@EnabledIfEnvironmentVariable(named = "WENJIN_EVAL_IT", matches = "true")
class HybridRetrievalIT extends EvalDbSupport {

    @Autowired private EvalHarness harness;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FeatureFlagsProperties flags;
    @Autowired private LimitsProperties limits;
    @Autowired private RagEmbedService embedService;
    @Autowired private RetrieverPort retriever;
    @Autowired private FulltextRetriever fulltext;
    @Autowired private VectorIndexCache indexCache;

    private EvalWorld world;
    private boolean vectorBefore;
    private boolean hashingBefore;

    @BeforeEach
    void setUp() {
        world = harness.fixture().world();
        vectorBefore = flags.isRagVectorEnabled();
        hashingBefore = limits.getRag().isAllowHashingEmbedding();
        flags.setRagVectorEnabled(true);
        limits.getRag().setAllowHashingEmbedding(true);
        deleteCourseEmbeddings();
    }

    @AfterEach
    void tearDown() {
        deleteCourseEmbeddings();
        flags.setRagVectorEnabled(vectorBefore);
        limits.getRag().setAllowHashingEmbedding(hashingBefore);
        indexCache.invalidateCourse(world.courseId());
    }

    private void deleteCourseEmbeddings() {
        jdbc.update("DELETE e FROM doc_chunk_embedding e JOIN doc_chunk c ON c.id = e.chunk_id"
                + " JOIN doc_source s ON s.id = c.source_id WHERE s.course_id = ?", world.courseId());
    }

    @Test
    @DisplayName("嵌入作业体写向量并幂等；混合检索在真库可用且节点圈定生效；关开关回到纯全文")
    void embedThenHybridRetrieve() {
        assertThat(retriever).isInstanceOf(HybridRetriever.class);
        List<Long> sources = jdbc.queryForList(
                "SELECT id FROM doc_source WHERE course_id = ? AND status = 1", Long.class, world.courseId());
        assertThat(sources).isNotEmpty();

        // 向量未写入时：混合检索 = 纯全文（NO_VECTORS 回退）
        List<RetrievalHit> before = retriever.retrieve(world.courseId(), List.of(), "用例图 参与者", 3);
        assertThat(before).isEqualTo(fulltext.retrieve(world.courseId(), List.of(), "用例图 参与者", 3));

        int embedded = 0;
        for (Long sid : sources) {
            Map<String, Object> r = embedService.embedSource(sid, world.courseId(), null);
            assertThat(r).containsEntry("status", "DONE")
                    .containsEntry("model", "hash-ngram@" + ActiveEmbeddingResolver.HASHING_DIM);
            embedded += (Integer) r.get("embedded");
            assertThat(embedService.embeddedCount(sid, "hash-ngram@" + ActiveEmbeddingResolver.HASHING_DIM))
                    .isEqualTo(((Integer) r.get("total")).longValue());
        }
        assertThat(embedded).isPositive();
        for (Long sid : sources) {
            assertThat(embedService.embedSource(sid, world.courseId(), null)).containsEntry("embedded", 0);
        }

        // 嵌入完成事件已失效索引 → 混合检索加载真库向量，用例图讲义排第一
        List<RetrievalHit> hybrid = retriever.retrieve(world.courseId(), List.of(), "画用例图先确定系统边界", 3);
        assertThat(hybrid).isNotEmpty();
        assertThat(hybrid.get(0).content()).contains("用例图");
        assertThat(hybrid).allSatisfy(h -> assertThat(h.score()).isLessThan(1.0)); // RRF 融合分

        List<RetrievalHit> scoped = retriever.retrieve(world.courseId(), List.of("SE-2.1"), "画用例图先确定系统边界", 3);
        assertThat(scoped).allSatisfy(h -> assertThat(h.content()).contains("需求获取"));

        flags.setRagVectorEnabled(false);
        assertThat(retriever.retrieve(world.courseId(), List.of(), "用例图 参与者", 3))
                .isEqualTo(fulltext.retrieve(world.courseId(), List.of(), "用例图 参与者", 3));
    }
}
