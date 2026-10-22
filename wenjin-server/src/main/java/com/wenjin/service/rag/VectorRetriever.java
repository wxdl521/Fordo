package com.wenjin.service.rag;

import com.wenjin.ai.EmbeddingClient;
import com.wenjin.config.LimitsProperties;
import com.wenjin.mapper.DocChunkMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 向量检索（R2）：当前生效嵌入客户端（{@link ActiveEmbeddingResolver}）嵌入查询 →
 * 课程内存索引（{@link VectorIndexCache}）暴力余弦 → 回表取正文。
 * <p>节点圈定与 APPROVED / READY 过滤与 {@link FulltextRetriever} 语义一致。
 * 不实现 {@link RetrieverPort}（避免与 {@link HybridRetriever} 抢注入），由混合检索调用。
 */
@Component
public class VectorRetriever {

    public enum Status {
        /** 向量模式关（开关关 / 无可用嵌入客户端） */
        DISABLED,
        /** 该课程在当前模型下没有可检索向量 */
        NO_VECTORS,
        /** 已执行向量检索（命中可能为空） */
        OK
    }

    public record VectorSearch(Status status, String modelId, List<RetrievalHit> hits) {
        public VectorSearch {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }

        static VectorSearch of(Status status, String modelId) {
            return new VectorSearch(status, modelId, List.of());
        }
    }

    private final ActiveEmbeddingResolver resolver;
    private final VectorIndexCache indexCache;
    private final QueryEmbeddingCache queryCache;
    private final DocChunkMapper chunkMapper;
    private final LimitsProperties limits;

    public VectorRetriever(ActiveEmbeddingResolver resolver,
                           VectorIndexCache indexCache,
                           QueryEmbeddingCache queryCache,
                           DocChunkMapper chunkMapper,
                           LimitsProperties limits) {
        this.resolver = resolver;
        this.indexCache = indexCache;
        this.queryCache = queryCache;
        this.chunkMapper = chunkMapper;
        this.limits = limits;
    }

    /** 当前是否处于向量模式（开关开且有可用嵌入客户端）。 */
    public boolean enabled() {
        return resolver.vectorModeOn();
    }

    /**
     * @return 状态 + 命中（score = 余弦）；查询嵌入失败时抛出（由调用方回退全文）
     */
    public VectorSearch search(long courseId, List<String> nodeCodes, String query, int limit) {
        Optional<EmbeddingClient> client = resolver.resolve();
        if (client.isEmpty()) {
            return VectorSearch.of(Status.DISABLED, null);
        }
        String modelId = client.get().modelId();
        CourseVectorIndex index = indexCache.get(courseId, modelId);
        if (index == null || index.isEmpty()) {
            return VectorSearch.of(Status.NO_VECTORS, modelId);
        }
        if (!StringUtils.hasText(query) || limit <= 0) {
            return VectorSearch.of(Status.OK, modelId);
        }
        float[] q = queryCache.embedQuery(client.get(), query.trim());
        List<CourseVectorIndex.Scored> scored = index.search(
                q, nodeCodes == null || nodeCodes.isEmpty() ? null : nodeCodes, minScore(), limit);
        if (scored.isEmpty()) {
            return VectorSearch.of(Status.OK, modelId);
        }
        List<Long> ids = scored.stream().map(CourseVectorIndex.Scored::chunkId).toList();
        List<Map<String, Object>> rows = chunkMapper.selectHitRows(courseId, ids);
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                if (row != null && row.get("chunk_id") instanceof Number n) {
                    byId.put(n.longValue(), row);
                }
            }
        }
        List<RetrievalHit> hits = new ArrayList<>(scored.size());
        for (CourseVectorIndex.Scored s : scored) {
            Map<String, Object> row = byId.get(s.chunkId());
            if (row == null) {
                continue;
            }
            String content = toStr(row.get("content"));
            if (!StringUtils.hasText(content)) {
                continue;
            }
            hits.add(new RetrievalHit(s.chunkId(), content, toStr(row.get("source_title")),
                    toStr(row.get("meta_json")), s.score()));
        }
        return new VectorSearch(Status.OK, modelId, hits);
    }

    private double minScore() {
        return limits == null || limits.getRag() == null ? 0.2 : limits.getRag().getVectorMinScore();
    }

    private static String toStr(Object v) {
        return v == null ? null : v.toString();
    }
}
