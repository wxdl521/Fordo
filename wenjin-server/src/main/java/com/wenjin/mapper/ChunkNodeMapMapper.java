package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.ChunkNodeMap;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
