package com.wenjin.service.rag;

import com.wenjin.ai.EmbeddingClient;
import com.wenjin.ai.HashingEmbeddingClient;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 当前生效的嵌入客户端选择（R2）。每次调用现读开关与配置（管理后台可热改）。
 *
 * <ol>
 *   <li>{@code rag-enabled} 与 {@code rag-vector-enabled} 任一为假 → 向量模式关（{@link Optional#empty()}）；</li>
 *   <li>真实客户端 {@link EmbeddingClient#available()}（enabled 且有 key）→ 用它；</li>
 *   <li>否则若 {@code wenjin.limits.rag.allow-hashing-embedding=true}（开发 / e2e）→ 离线
 *       {@link HashingEmbeddingClient}（{@value #HASHING_DIM} 维，modelId {@code hash-ngram@1024}）；</li>
 *   <li>否则 → 向量模式关（生产默认）。</li>
 * </ol>
 * 向量存储与内存索引一律按 {@link EmbeddingClient#modelId()} 隔离，切换模型不会混用向量（需「重建向量」回填）。
 */
@Component
public class ActiveEmbeddingResolver {

    /** 离线哈希嵌入维度（固定，modelId 随之固定）。 */
    public static final int HASHING_DIM = 1024;

    private final FeatureFlagsProperties featureFlags;
    private final LimitsProperties limits;
    private final EmbeddingClient realClient;
    private final HashingEmbeddingClient hashingClient = new HashingEmbeddingClient(HASHING_DIM);

    /**
     * @param realClient 真实嵌入客户端（生产为 {@code OpenAiCompatibleEmbeddingClient}，唯一 EmbeddingClient bean）；
     *                   测试 / 评测可传任意实现或 null
     */
    public ActiveEmbeddingResolver(FeatureFlagsProperties featureFlags,
                                   LimitsProperties limits,
                                   @Nullable EmbeddingClient realClient) {
        this.featureFlags = featureFlags;
        this.limits = limits;
        this.realClient = realClient;
    }

    /** 当前生效的嵌入客户端；空表示向量模式关（检索纯全文、不派发嵌入作业）。 */
    public Optional<EmbeddingClient> resolve() {
        if (featureFlags == null || !featureFlags.isRagEnabled() || !featureFlags.isRagVectorEnabled()) {
            return Optional.empty();
        }
        if (realClient != null && realClient.available()) {
            return Optional.of(realClient);
        }
        if (limits != null && limits.getRag() != null && limits.getRag().isAllowHashingEmbedding()) {
            return Optional.of(hashingClient);
        }
        return Optional.empty();
    }

    public boolean vectorModeOn() {
        return resolve().isPresent();
    }
}
