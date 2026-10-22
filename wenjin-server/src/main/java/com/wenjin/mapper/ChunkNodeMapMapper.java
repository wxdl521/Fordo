package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.ChunkNodeMap;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * chunk↔节点映射 Mapper（M8）。
 */
@Mapper
public interface ChunkNodeMapMapper extends BaseMapper<ChunkNodeMap> {

    /**
     * 半成品清障：删除某 source 下全部 chunk 的映射。
     */
    @Delete("""
            DELETE m FROM chunk_node_map m
            INNER JOIN doc_chunk c ON c.id = m.chunk_id
            WHERE c.source_id = #{sourceId}
            """)
    int deleteBySourceId(@Param("sourceId") Long sourceId);

    /**
     * 向量索引用：课程内 READY 来源、APPROVED 映射的 (chunkId, nodeCode)（与全文检索同一可检索语义）。
     */
    @Select("""
            SELECT m.chunk_id AS chunkId, m.node_code AS nodeCode
            FROM chunk_node_map m
            INNER JOIN doc_chunk c ON c.id = m.chunk_id
            INNER JOIN doc_source s ON s.id = c.source_id
            WHERE m.course_id = #{courseId}
              AND s.course_id = #{courseId}
              AND s.status = 1
              AND m.review_status = 1
            """)
    List<ChunkNodeMap> selectApprovedByCourse(@Param("courseId") long courseId);
}
