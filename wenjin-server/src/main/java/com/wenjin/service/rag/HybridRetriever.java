package com.wenjin.service.rag;

import com.wenjin.config.LimitsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 混合检索（R2，{@link RetrieverPort} 的生效实现）：全文与向量各取 topK × candidate-multiplier →
 * RRF 融合（score = Σ 1/(fusion-k + rank)）→ topK；命中 score 为融合分。
 *
 * <p>回退（直接返回 {@code fulltext.retrieve(courseId, nodeCodes, query, topK)}，与 M8 纯全文逐字节一致）：
 * 向量模式关（开关关 / 无可用嵌入客户端）、该课程当前模型无向量、向量检索抛错（只 warn 不抛）。
 * 向量检索执行成功但无命中时，返回全文候选的前 topK（原 MATCH 分）。
 *
 * <p>R2 复核：①向量侧失败（超时 / 报错）后进入冷却（{@code vector-cooldown-seconds}），期间直接纯全文，
 * 避免每次对话都等一遍超时；②仅向量命中（不在全文候选里）的块须达到 {@code vector-only-min-score}，
 * 同时被全文命中的块沿用较低的 {@code vector-min-score}。
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
    private final Clock clock;
    /** 冷却截止（epoch millis）；进程内共享，向量侧恢复靠冷却到期后的下一次探测。 */
    private volatile long vectorSkipUntilMs;

    @Autowired
    public HybridRetriever(FulltextRetriever fulltext, VectorRetriever vector, LimitsProperties limits, Clock clock) {
        this((RetrieverPort) fulltext, vector, limits, clock);
    }

    /** 测试 / 评测：全文侧可替换（如内存实现）。 */
    public HybridRetriever(RetrieverPort fulltext, VectorRetriever vector, LimitsProperties limits) {
        this(fulltext, vector, limits, Clock.systemUTC());
    }

    public HybridRetriever(RetrieverPort fulltext, VectorRetriever vector, LimitsProperties limits, Clock clock) {
        this.fulltext = Objects.requireNonNull(fulltext, "fulltext");
        this.vector = vector;
        this.limits = limits;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public List<RetrievalHit> retrieve(long courseId, List<String> nodeCodes, String query, int topK) {
        if (vector == null || !StringUtils.hasText(query) || topK <= 0) {
            return fulltext.retrieve(courseId, nodeCodes, query, topK);
        }
        if (clock.millis() < vectorSkipUntilMs) {
            return fulltextOnly(courseId, nodeCodes, query, topK, "COOLDOWN");
        }
        int candidates = topK * Math.max(1, multiplier());
        VectorRetriever.VectorSearch vs;
        try {
            vs = vector.search(courseId, nodeCodes, query, candidates);
        } catch (RuntimeException e) {
            long cool = cooldownSeconds() * 1000L;
            if (cool > 0) {
                vectorSkipUntilMs = clock.millis() + cool;
            }
            log.warn("rag vector search failed, fallback FULLTEXT courseId={} cooldown={}s: {}",
                    courseId, cooldownSeconds(), e.getMessage());
            return fulltextOnly(courseId, nodeCodes, query, topK, "VECTOR_ERROR");
        }
        if (vs == null || vs.status() != VectorRetriever.Status.OK) {
            return fulltextOnly(courseId, nodeCodes, query, topK,
                    vs == null ? "VECTOR_NULL" : vs.status().name());
        }
        List<RetrievalHit> ft = fulltext.retrieve(courseId, nodeCodes, query, candidates);
        ft = ft == null ? List.of() : ft;
        List<RetrievalHit> vecHits = filterVectorOnly(vs.hits(), ft);
        if (vecHits.isEmpty()) {
            log.debug("rag retrieve mode={} courseId={} reason=NO_VECTOR_HITS", Mode.FULLTEXT, courseId);
            return ft.size() > topK ? List.copyOf(ft.subList(0, topK)) : ft;
        }
        List<RetrievalHit> fused = RrfFusion.fuse(List.of(ft, vecHits), fusionK(), topK);
        log.debug("rag retrieve mode={} courseId={} model={} ft={} vec={} out={}",
                Mode.HYBRID, courseId, vs.modelId(), ft.size(), vecHits.size(), fused.size());
        return fused;
    }

    /** 仅向量命中的块须 ≥ vector-only-min-score；全文也命中的块不受影响。 */
    private List<RetrievalHit> filterVectorOnly(List<RetrievalHit> vecHits, List<RetrievalHit> ft) {
        double floor = vectorOnlyMinScore();
        Set<Long> inFulltext = new HashSet<>();
        for (RetrievalHit h : ft) {
            inFulltext.add(h.chunkId());
        }
        return vecHits.stream()
                .filter(h -> inFulltext.contains(h.chunkId()) || h.score() >= floor)
                .toList();
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

    private double vectorOnlyMinScore() {
        return limits == null || limits.getRag() == null ? 0.5 : limits.getRag().getVectorOnlyMinScore();
    }

    private int cooldownSeconds() {
        return limits == null || limits.getRag() == null ? 60 : Math.max(0, limits.getRag().getVectorCooldownSeconds());
    }

    private int multiplier() {
        return limits == null || limits.getRag() == null ? 3 : limits.getRag().getCandidateMultiplier();
    }

    private int fusionK() {
        return limits == null || limits.getRag() == null ? 60 : limits.getRag().getFusionK();
    }
}
