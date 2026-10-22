package com.wenjin.service.rag;

import com.wenjin.config.LimitsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 混合检索（R2，{@link RetrieverPort} 的生效实现）：全文与向量各取 topK × candidate-multiplier →
 * RRF 融合（score = Σ 1/(fusion-k + rank)）→ topK；命中 score 为融合分。
 *
 * <p>回退（直接返回 {@code fulltext.retrieve(courseId, nodeCodes, query, topK)}，与 M8 纯全文逐字节一致）：
 * 向量模式关（开关关 / 无可用嵌入客户端）、该课程当前模型无向量、向量检索抛错（只 warn 不抛）。
 * 向量检索执行成功但无命中时，返回全文候选的前 topK（原 MATCH 分）。
 * 日志 mode=FULLTEXT|HYBRID 标明实际路径。
 */
@Primary
@Component
public class HybridRetriever implements RetrieverPort {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    public enum Mode { FULLTEXT, HYBRID }

    private final RetrieverPort fulltext;
    private final VectorRetriever vector;
    private final LimitsProperties limits;

    @Autowired
    public HybridRetriever(FulltextRetriever fulltext, VectorRetriever vector, LimitsProperties limits) {
        this((RetrieverPort) fulltext, vector, limits);
    }

    /** 测试 / 评测：全文侧可替换（如内存实现）。 */
    public HybridRetriever(RetrieverPort fulltext, VectorRetriever vector, LimitsProperties limits) {
        this.fulltext = Objects.requireNonNull(fulltext, "fulltext");
        this.vector = vector;
        this.limits = limits;
    }

    @Override
    public List<RetrievalHit> retrieve(long courseId, List<String> nodeCodes, String query, int topK) {
        if (vector == null || !StringUtils.hasText(query) || topK <= 0) {
            return fulltext.retrieve(courseId, nodeCodes, query, topK);
        }
        int candidates = topK * Math.max(1, multiplier());
        VectorRetriever.VectorSearch vs;
        try {
            vs = vector.search(courseId, nodeCodes, query, candidates);
        } catch (RuntimeException e) {
            log.warn("rag vector search failed, fallback FULLTEXT courseId={}: {}", courseId, e.getMessage());
            return fulltextOnly(courseId, nodeCodes, query, topK, "VECTOR_ERROR");
        }
        if (vs == null || vs.status() != VectorRetriever.Status.OK) {
            return fulltextOnly(courseId, nodeCodes, query, topK,
                    vs == null ? "VECTOR_NULL" : vs.status().name());
        }
        List<RetrievalHit> ft = fulltext.retrieve(courseId, nodeCodes, query, candidates);
        ft = ft == null ? List.of() : ft;
        if (vs.hits().isEmpty()) {
            log.debug("rag retrieve mode={} courseId={} reason=NO_VECTOR_HITS", Mode.FULLTEXT, courseId);
            return ft.size() > topK ? List.copyOf(ft.subList(0, topK)) : ft;
        }
        List<RetrievalHit> fused = RrfFusion.fuse(List.of(ft, vs.hits()), fusionK(), topK);
        log.debug("rag retrieve mode={} courseId={} model={} ft={} vec={} out={}",
                Mode.HYBRID, courseId, vs.modelId(), ft.size(), vs.hits().size(), fused.size());
        return fused;
    }

    /** 当前请求将走的模式（仅看开关与客户端，不看课程向量是否存在）。 */
    public Mode configuredMode() {
        return vector != null && vector.enabled() ? Mode.HYBRID : Mode.FULLTEXT;
    }

    private List<RetrievalHit> fulltextOnly(long courseId, List<String> nodeCodes, String query, int topK,
                                            String reason) {
        log.debug("rag retrieve mode={} courseId={} reason={}", Mode.FULLTEXT, courseId, reason);
        return fulltext.retrieve(courseId, nodeCodes, query, topK);
    }

    private int multiplier() {
        return limits == null || limits.getRag() == null ? 3 : limits.getRag().getCandidateMultiplier();
    }

    private int fusionK() {
        return limits == null || limits.getRag() == null ? 60 : limits.getRag().getFusionK();
    }
}
