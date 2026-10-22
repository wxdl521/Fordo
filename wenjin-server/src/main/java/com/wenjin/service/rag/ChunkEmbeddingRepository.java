package com.wenjin.service.rag;

import com.wenjin.entity.DocChunkEmbedding;
import com.wenjin.mapper.DocChunkEmbeddingMapper;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 切块向量仓库：upsert、按 课程+模型 批量加载（仅 READY 来源）、按 chunkIds / sourceId 删除。
 */
@Repository
public class ChunkEmbeddingRepository {

    /** 加载结果：解码后的向量。 */
    public record StoredEmbedding(long chunkId, float[] vector, String contentSha) {
    }

    private final DocChunkEmbeddingMapper mapper;

    public ChunkEmbeddingRepository(DocChunkEmbeddingMapper mapper) {
        this.mapper = mapper;
    }

    /** 插入或覆盖某 chunk 的向量。 */
    public void upsert(long chunkId, String model, float[] vector, String contentSha) {
        mapper.upsert(chunkId, model, vector.length, EmbeddingCodec.encode(vector), contentSha);
    }

    /** 某课程某模型下全部 READY 来源的向量；维度与 dim 列不符的脏行跳过。 */
    public List<StoredEmbedding> loadByCourseAndModel(long courseId, String model) {
        return mapper.selectByCourseAndModel(courseId, model).stream()
                .map(e -> {
                    float[] v = EmbeddingCodec.decode(e.getVector());
                    return v.length == (e.getDim() == null ? -1 : e.getDim())
                            ? new StoredEmbedding(e.getChunkId(), v, e.getContentSha()) : null;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** chunkId → 已存 content_sha（仅该模型）；嵌入作业据此跳过未变内容。 */
    public Map<Long, String> loadShaByChunkIds(String model, Collection<Long> chunkIds) {
        Map<Long, String> out = new HashMap<>();
        if (chunkIds == null || chunkIds.isEmpty()) {
            return out;
        }
        for (DocChunkEmbedding e : mapper.selectShaByChunkIds(model, chunkIds)) {
            out.put(e.getChunkId(), e.getContentSha());
        }
        return out;
    }

    public int deleteByChunkIds(Collection<Long> chunkIds) {
        return (chunkIds == null || chunkIds.isEmpty()) ? 0 : mapper.deleteByChunkIds(chunkIds);
    }

    public int deleteBySourceId(long sourceId) {
        return mapper.deleteBySourceId(sourceId);
    }

    /** chunk 内容的 SHA-256 hex（小写 64 位）。 */
    public static String contentSha(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
