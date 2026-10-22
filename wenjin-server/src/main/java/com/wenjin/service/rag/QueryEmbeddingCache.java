package com.wenjin.service.rag;

import com.wenjin.ai.EmbeddingClient;
import com.wenjin.config.LimitsProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 查询向量 LRU 缓存（R2）：键 = modelId + 查询串，容量 {@code wenjin.limits.rag.query-cache-size}（0 关闭）。
 * 按 modelId 区分，切换嵌入模型不会复用旧模型的向量。
 */
@Component
public class QueryEmbeddingCache {

    private final LimitsProperties limits;
    private final LinkedHashMap<String, float[]> lru = new LinkedHashMap<>(64, 0.75f, true);

    public QueryEmbeddingCache(LimitsProperties limits) {
        this.limits = limits;
    }

    /** 命中返回缓存；否则调用 client.embed 并缓存。嵌入失败原样抛出。 */
    public float[] embedQuery(EmbeddingClient client, String query) {
        String key = client.modelId() + "\u0000" + query;
        int cap = capacity();
        if (cap > 0) {
            synchronized (lru) {
                float[] hit = lru.get(key);
                if (hit != null) {
                    return hit;
                }
            }
        }
        List<float[]> out = client.embedForQuery(List.of(query), Duration.ofMillis(timeoutMs()));
        if (out == null || out.size() != 1 || out.get(0) == null) {
            throw new IllegalStateException("查询嵌入返回为空");
        }
        float[] v = out.get(0);
        if (cap > 0) {
            synchronized (lru) {
                lru.put(key, v);
                Iterator<String> it = lru.keySet().iterator();
                while (lru.size() > cap && it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }
        return v;
    }

    public int size() {
        synchronized (lru) {
            return lru.size();
        }
    }

    public void clear() {
        synchronized (lru) {
            lru.clear();
        }
    }

    private int timeoutMs() {
        return limits == null || limits.getRag() == null ? 2500 : limits.getRag().getQueryEmbedTimeoutMs();
    }

    private int capacity() {
        return limits == null || limits.getRag() == null ? 0 : Math.max(0, limits.getRag().getQueryCacheSize());
    }
}
