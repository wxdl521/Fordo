package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.DocChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 语料切块 Mapper（M8）。
 */
@Mapper
public interface DocChunkMapper extends BaseMapper<DocChunk> {

    /** 未关联块数：该 source 下没有 PENDING/APPROVED 映射的 chunk（不可检索）。 */
    @Select("""
            SELECT COUNT(*) FROM doc_chunk c
            WHERE c.source_id = #{sourceId}
              AND NOT EXISTS (SELECT 1 FROM chunk_node_map m
                              WHERE m.chunk_id = c.id AND m.review_status IN (0, 1))
            """)
    long countUnlinkedBySource(@Param("sourceId") Long sourceId);

    /** 半成品清障：删除某 source 下全部 chunk（映射需先删）。 */
    @Delete("DELETE FROM doc_chunk WHERE source_id = #{sourceId}")
    int deleteBySourceId(@Param("sourceId") Long sourceId);

    /**
     * FULLTEXT 检索：仅 READY 来源 + APPROVED 映射；可选节点圈定。
     * 返回列：chunk_id, content, source_title, meta_json, score。
     */
    @Select("""
            <script>
            SELECT c.id AS chunk_id,
                   c.content AS content,
                   s.title AS source_title,
                   c.meta_json AS meta_json,
                   MATCH(c.content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS score
            FROM doc_chunk c
            INNER JOIN doc_source s ON s.id = c.source_id
            INNER JOIN chunk_node_map m ON m.chunk_id = c.id
            WHERE s.course_id = #{courseId}
              AND s.status = 1
              AND m.review_status = 1
              AND m.course_id = #{courseId}
              AND MATCH(c.content) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
            <if test="nodeCodes != null and nodeCodes.size() &gt; 0">
              AND m.node_code IN
              <foreach collection="nodeCodes" item="nc" open="(" separator="," close=")">
                #{nc}
              </foreach>
            </if>
            GROUP BY c.id, c.content, s.title, c.meta_json
            HAVING score &gt; 0
            ORDER BY score DESC
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> matchAgainst(@Param("courseId") long courseId,
                                          @Param("query") String query,
                                          @Param("nodeCodes") List<String> nodeCodes,
                                          @Param("limit") int limit);

    /**
     * 向量命中回表：按 chunkId 取命中所需列（仅本课程 READY 来源）。
     * 返回列：chunk_id, content, source_title, meta_json。
     */
    @Select("""
            <script>
            SELECT c.id AS chunk_id,
                   c.content AS content,
                   s.title AS source_title,
                   c.meta_json AS meta_json
            FROM doc_chunk c
            INNER JOIN doc_source s ON s.id = c.source_id
            WHERE s.course_id = #{courseId}
              AND s.status = 1
              AND c.id IN
              <foreach collection="chunkIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Map<String, Object>> selectHitRows(@Param("courseId") long courseId,
                                            @Param("chunkIds") java.util.Collection<Long> chunkIds);
}
