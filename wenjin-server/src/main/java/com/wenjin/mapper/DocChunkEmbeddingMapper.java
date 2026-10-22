package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.DocChunkEmbedding;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 语料切块向量 Mapper（V23）。
 */
@Mapper
public interface DocChunkEmbeddingMapper extends BaseMapper<DocChunkEmbedding> {

    @Insert("""
            INSERT INTO doc_chunk_embedding (chunk_id, model, dim, vector, content_sha, created_at)
            VALUES (#{chunkId}, #{model}, #{dim}, #{vector}, #{contentSha}, NOW())
            ON DUPLICATE KEY UPDATE model = VALUES(model), dim = VALUES(dim), vector = VALUES(vector),
                                    content_sha = VALUES(content_sha), created_at = NOW()
            """)
    int upsert(@Param("chunkId") long chunkId, @Param("model") String model, @Param("dim") int dim,
               @Param("vector") byte[] vector, @Param("contentSha") String contentSha);

    /** 某课程某模型下、READY 来源的全部向量。 */
    @Select("""
            SELECT e.chunk_id AS chunkId, e.model AS model, e.dim AS dim, e.vector AS vector,
                   e.content_sha AS contentSha, e.created_at AS createdAt
            FROM doc_chunk_embedding e
            INNER JOIN doc_chunk c ON c.id = e.chunk_id
            INNER JOIN doc_source s ON s.id = c.source_id
            WHERE s.course_id = #{courseId} AND s.status = 1 AND e.model = #{model}
            """)
    List<DocChunkEmbedding> selectByCourseAndModel(@Param("courseId") long courseId, @Param("model") String model);

    /** 指定 chunk 在该模型下的已存 sha（不取 vector），用于嵌入作业跳过未变内容。 */
    @Select("""
            <script>
            SELECT chunk_id AS chunkId, content_sha AS contentSha
            FROM doc_chunk_embedding
            WHERE model = #{model} AND chunk_id IN
            <foreach collection="chunkIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<DocChunkEmbedding> selectShaByChunkIds(@Param("model") String model,
                                                @Param("chunkIds") Collection<Long> chunkIds);

    @Delete("""
            <script>
            DELETE FROM doc_chunk_embedding WHERE chunk_id IN
            <foreach collection="chunkIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int deleteByChunkIds(@Param("chunkIds") Collection<Long> chunkIds);

    /** 删除某来源下全部 chunk 的向量（来源重建/删除时用）。 */
    @Delete("""
            DELETE FROM doc_chunk_embedding
            WHERE chunk_id IN (SELECT id FROM doc_chunk WHERE source_id = #{sourceId})
            """)
    int deleteBySourceId(@Param("sourceId") long sourceId);
}
