package com.wenjin.service.rag;

import com.wenjin.entity.ChunkNodeMap;
import com.wenjin.mapper.ChunkNodeMapMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 课程向量索引缓存（R2），键 = (courseId, modelId)：切换嵌入模型时新旧向量天然隔离。
 * <p>失效点（{@link #invalidateCourse(long)}，经 {@link RagCorpusEventListener} 在事务提交后触发）：
 * 入库完成、嵌入作业完成、映射通过 / 驳回 / 批量通过、来源归档（当前无归档入口，接入时发
 * {@link RagCorpusChangedEvent} 即可）。加载期间若发生失效，按课程版本号丢弃过期结果不入缓存。
 */
@Component
public class VectorIndexCache {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexCache.class);

    private record Key(long courseId, String modelId) {
    }

    private final ChunkEmbeddingRepository embeddingRepository;
    private final ChunkNodeMapMapper mapMapper;
    private final ConcurrentHashMap<Key, CourseVectorIndex> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> versions = new ConcurrentHashMap<>();

    public VectorIndexCache(ChunkEmbeddingRepository embeddingRepository, ChunkNodeMapMapper mapMapper) {
        this.embeddingRepository = embeddingRepository;
        this.mapMapper = mapMapper;
    }

    /** 取（必要时加载）某课程某模型的索引；无向量时返回空索引（同样缓存，直到失效）。 */
    public CourseVectorIndex get(long courseId, String modelId) {
        Key key = new Key(courseId, modelId);
        CourseVectorIndex idx = cache.get(key);
        if (idx != null) {
            return idx;
        }
        AtomicLong version = versions.computeIfAbsent(courseId, k -> new AtomicLong());
        long before = version.get();
        CourseVectorIndex loaded = load(courseId, modelId);
        if (version.get() == before) {
            cache.put(key, loaded);
            if (version.get() != before) {
                cache.remove(key, loaded);
            }
        }
        return loaded;
    }

    /** 让某课程全部模型的索引失效（下次检索重载）。 */
    public void invalidateCourse(long courseId) {
        versions.computeIfAbsent(courseId, k -> new AtomicLong()).incrementAndGet();
        cache.keySet().removeIf(k -> k.courseId() == courseId);
        log.debug("rag vector index invalidated courseId={}", courseId);
    }

    public void invalidateAll() {
        versions.values().forEach(AtomicLong::incrementAndGet);
        cache.clear();
    }

    /** 测试 / 观测：当前是否已缓存。 */
    public boolean isCached(long courseId, String modelId) {
        return cache.containsKey(new Key(courseId, modelId));
    }

    CourseVectorIndex load(long courseId, String modelId) {
        List<ChunkEmbeddingRepository.StoredEmbedding> stored =
                embeddingRepository.loadByCourseAndModel(courseId, modelId);
        if (stored == null || stored.isEmpty()) {
            return new CourseVectorIndex(modelId, List.of());
        }
        Map<Long, Set<String>> approved = new HashMap<>();
        List<ChunkNodeMap> maps = mapMapper.selectApprovedByCourse(courseId);
        if (maps != null) {
            for (ChunkNodeMap m : maps) {
                if (m != null && m.getChunkId() != null && StringUtils.hasText(m.getNodeCode())) {
                    approved.computeIfAbsent(m.getChunkId(), k -> new HashSet<>()).add(m.getNodeCode());
                }
            }
        }
        List<CourseVectorIndex.Entry> entries = new ArrayList<>();
        for (ChunkEmbeddingRepository.StoredEmbedding e : stored) {
            Set<String> nodes = approved.get(e.chunkId());
            if (nodes != null && !nodes.isEmpty()) {
                entries.add(new CourseVectorIndex.Entry(e.chunkId(), e.vector(), nodes));
            }
        }
        log.debug("rag vector index loaded courseId={} model={} entries={}", courseId, modelId, entries.size());
        return new CourseVectorIndex(modelId, entries);
    }
}
