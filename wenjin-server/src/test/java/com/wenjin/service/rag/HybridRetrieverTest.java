package com.wenjin.service.rag;

import com.wenjin.ai.EmbeddingClient;
import com.wenjin.ai.HashingEmbeddingClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.ChunkNodeMap;
import com.wenjin.mapper.ChunkNodeMapMapper;
import com.wenjin.mapper.DocChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R2 混合检索：RRF 融合、回退各分支（逐字节等于纯全文）、索引失效、节点 / APPROVED 过滤、开关矩阵。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HybridRetrieverTest {

    private static final long COURSE = 7L;
    private static final HashingEmbeddingClient HASH = new HashingEmbeddingClient(ActiveEmbeddingResolver.HASHING_DIM);

    @Mock private ChunkEmbeddingRepository embeddingRepository;
    @Mock private ChunkNodeMapMapper mapMapper;
    @Mock private DocChunkMapper chunkMapper;

    private FeatureFlagsProperties flags;
    private LimitsProperties limits;
    private RecordingFulltext fulltext;
    private VectorIndexCache indexCache;
    private HybridRetriever hybrid;

    /** 语料：chunkId → (内容, APPROVED 节点) */
    private final Map<Long, String> contents = new LinkedHashMap<>();
    private final Map<Long, Set<String>> approved = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        flags = new FeatureFlagsProperties();
        limits = new LimitsProperties();
        limits.getRag().setVectorMinScore(0.05);
        fulltext = new RecordingFulltext();
        indexCache = new VectorIndexCache(embeddingRepository, mapMapper);
        ActiveEmbeddingResolver resolver = new ActiveEmbeddingResolver(flags, limits, null); // → 哈希嵌入
        VectorRetriever vector = new VectorRetriever(resolver, indexCache, new QueryEmbeddingCache(limits),
                chunkMapper, limits);
        hybrid = new HybridRetriever(fulltext, vector, limits);

        corpus(11, "二叉树的前序遍历先访问根节点", "N1");
        corpus(12, "栈是一种后进先出的线性表", "N2");
        corpus(13, "先根遍历即根左右的访问顺序", "N1");
        corpus(14, "未审核块：先根遍历", null); // 有向量但无 APPROVED 映射 → 不进索引
        stubStorage();
    }

    private void corpus(long id, String content, String node) {
        contents.put(id, content);
        if (node != null) {
            approved.put(id, Set.of(node));
        }
    }

    private void stubStorage() {
        when(embeddingRepository.loadByCourseAndModel(eq(COURSE), eq(HASH.modelId()))).thenAnswer(inv -> {
            List<ChunkEmbeddingRepository.StoredEmbedding> out = new ArrayList<>();
            contents.forEach((id, c) -> out.add(new ChunkEmbeddingRepository.StoredEmbedding(
                    id, HASH.embed(List.of(c)).get(0), "sha")));
            return out;
        });
        when(mapMapper.selectApprovedByCourse(COURSE)).thenAnswer(inv -> {
            List<ChunkNodeMap> out = new ArrayList<>();
            approved.forEach((id, nodes) -> nodes.forEach(n -> {
                ChunkNodeMap m = new ChunkNodeMap();
                m.setChunkId(id);
                m.setNodeCode(n);
                out.add(m);
            }));
            return out;
        });
        when(chunkMapper.selectHitRows(eq(COURSE), anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(1);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Long id : ids) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("chunk_id", id);
                row.put("content", contents.get(id));
                row.put("source_title", "教材");
                row.put("meta_json", "{}");
                rows.add(row);
            }
            return rows;
        });
    }

    @Test
    @DisplayName("HYBRID：全文 + 向量各取 topK×multiplier，RRF 融合；同义改写（全文零命中）也能召回")
    void hybridFusesAndRecallsParaphrase() {
        fulltext.respond(List.of(ft(11, 5.0)));

        List<RetrievalHit> out = hybrid.retrieve(COURSE, List.of(), "先根遍历的顺序", 2);

        assertThat(fulltext.calls).containsExactly(new Call(COURSE, List.of(), "先根遍历的顺序", 6));
        assertThat(out).hasSize(2);
        assertThat(out).extracting(RetrievalHit::chunkId).contains(13L);
        assertThat(out).extracting(RetrievalHit::chunkId).doesNotContain(14L);
        // 融合分而非 MATCH 分
        assertThat(out).allSatisfy(h -> assertThat(h.score()).isLessThan(1.0));
    }

    @Test
    @DisplayName("节点圈定：向量侧只在范围内的 APPROVED 块中检索")
    void nodeScopeFilter() {
        fulltext.respond(List.of());

        List<RetrievalHit> out = hybrid.retrieve(COURSE, List.of("N2"), "先根遍历的顺序 栈", 5);

        assertThat(out).extracting(RetrievalHit::chunkId).containsOnly(12L);
    }

    @Test
    @DisplayName("回退：rag-vector-enabled 关 → 纯全文，参数与返回逐字节一致（topK 不放大、分数原样）")
    void fallbackVectorFlagOff() {
        flags.setRagVectorEnabled(false);
        List<RetrievalHit> ftHits = List.of(ft(11, 3.25), ft(12, 1.5));
        fulltext.respond(ftHits);

        List<RetrievalHit> out = hybrid.retrieve(COURSE, List.of("N1"), "遍历", 4);

        assertThat(out).isSameAs(ftHits);
        assertThat(fulltext.calls).containsExactly(new Call(COURSE, List.of("N1"), "遍历", 4));
        verify(embeddingRepository, never()).loadByCourseAndModel(anyLong(), any());
    }

    @Test
    @DisplayName("回退：rag-enabled 关 → 纯全文（开关矩阵）")
    void fallbackRagOff() {
        flags.setRagEnabled(false);
        List<RetrievalHit> ftHits = List.of(ft(11, 3.25));
        fulltext.respond(ftHits);
        assertThat(hybrid.retrieve(COURSE, List.of(), "遍历", 4)).isSameAs(ftHits);
        assertThat(hybrid.configuredMode()).isEqualTo(HybridRetriever.Mode.FULLTEXT);
    }

    @Test
    @DisplayName("回退：无可用嵌入客户端（生产禁哈希且无真实 key）→ 纯全文")
    void fallbackNoClient() {
        limits.getRag().setAllowHashingEmbedding(false);
        List<RetrievalHit> ftHits = List.of(ft(11, 3.25));
        fulltext.respond(ftHits);
        assertThat(hybrid.retrieve(COURSE, List.of(), "遍历", 4)).isSameAs(ftHits);
        assertThat(fulltext.calls).containsExactly(new Call(COURSE, List.of(), "遍历", 4));
    }

    @Test
    @DisplayName("回退：该课程当前模型无向量 → 纯全文")
    void fallbackNoVectors() {
        contents.clear();
        List<RetrievalHit> ftHits = List.of(ft(11, 3.25));
        fulltext.respond(ftHits);
        assertThat(hybrid.retrieve(COURSE, List.of(), "遍历", 4)).isSameAs(ftHits);
        assertThat(fulltext.calls).containsExactly(new Call(COURSE, List.of(), "遍历", 4));
    }

    @Test
    @DisplayName("回退：查询嵌入失败只 warn 不抛 → 纯全文")
    void fallbackEmbedError() {
        EmbeddingClient broken = new EmbeddingClient() {
            @Override
            public List<float[]> embed(List<String> texts) {
                throw new BusinessException(ResultCode.AI_ERROR, "down");
            }

            @Override
            public String modelId() {
                return HASH.modelId();
            }

            @Override
            public boolean available() {
                return true;
            }
        };
        VectorRetriever vector = new VectorRetriever(new ActiveEmbeddingResolver(flags, limits, broken), indexCache,
                new QueryEmbeddingCache(limits), chunkMapper, limits);
        HybridRetriever h = new HybridRetriever(fulltext, vector, limits);
        List<RetrievalHit> ftHits = List.of(ft(11, 3.25));
        fulltext.respond(ftHits);

        assertThat(h.retrieve(COURSE, List.of(), "遍历", 4)).isSameAs(ftHits);
        assertThat(fulltext.calls).containsExactly(new Call(COURSE, List.of(), "遍历", 4));
    }

    @Test
    @DisplayName("向量检索执行但无命中 → 全文候选前 topK，保留原 MATCH 分")
    void vectorNoHitsKeepsFulltextScores() {
        limits.getRag().setVectorMinScore(0.99);
        fulltext.respond(List.of(ft(11, 3.0), ft(12, 2.0), ft(13, 1.0)));

        List<RetrievalHit> out = hybrid.retrieve(COURSE, List.of(), "完全无关的查询词汇", 2);

        assertThat(out).extracting(RetrievalHit::chunkId).containsExactly(11L, 12L);
        assertThat(out).extracting(RetrievalHit::score).containsExactly(3.0, 2.0);
    }

    @Test
    @DisplayName("索引失效：同课程重复检索只加载一次；invalidateCourse 后重载并反映新的 APPROVED 映射")
    void indexInvalidation() {
        fulltext.respond(List.of());
        hybrid.retrieve(COURSE, List.of(), "先根遍历", 3);
        hybrid.retrieve(COURSE, List.of(), "先根遍历", 3);
        verify(embeddingRepository, times(1)).loadByCourseAndModel(COURSE, HASH.modelId());
        assertThat(hybrid.retrieve(COURSE, List.of(), "未审核块：先根遍历", 5))
                .extracting(RetrievalHit::chunkId).doesNotContain(14L);

        approved.put(14L, Set.of("N1")); // 老师通过了映射
        indexCache.invalidateCourse(COURSE);
        assertThat(indexCache.isCached(COURSE, HASH.modelId())).isFalse();

        assertThat(hybrid.retrieve(COURSE, List.of(), "未审核块：先根遍历", 5))
                .extracting(RetrievalHit::chunkId).contains(14L);
        verify(embeddingRepository, times(2)).loadByCourseAndModel(COURSE, HASH.modelId());
    }

    @Test
    @DisplayName("RetrievalService 两段式（圈内空 → 放宽）规则不变：两次调用都经混合检索")
    void retrievalServiceWidenUnchanged() {
        fulltext.respond(List.of());
        RetrievalService service = new RetrievalService(hybrid);
        RetrievalService.RetrievalResult r = service.search(COURSE, List.of("N9"), "栈 后进先出", 3, true);
        assertThat(r.scopeWidened()).isTrue();
        assertThat(r.hits()).extracting(RetrievalHit::chunkId).contains(12L);
    }

    private static RetrievalHit ft(long id, double score) {
        return new RetrievalHit(id, "ft-" + id, "教材", "{}", score);
    }

    record Call(long courseId, List<String> nodeCodes, String query, int topK) {
    }

    static final class RecordingFulltext implements RetrieverPort {
        final List<Call> calls = new ArrayList<>();
        private List<RetrievalHit> response = List.of();

        void respond(List<RetrievalHit> hits) {
            this.response = hits;
        }

        @Override
        public List<RetrievalHit> retrieve(long courseId, List<String> nodeCodes, String query, int topK) {
            calls.add(new Call(courseId, nodeCodes, query, topK));
            return response;
        }
    }
}
