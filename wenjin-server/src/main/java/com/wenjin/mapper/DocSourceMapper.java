package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.DocSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 语料来源 Mapper（M8）。
 */
@Mapper
public interface DocSourceMapper extends BaseMapper<DocSource> {

    @Update("UPDATE doc_source SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") int status);

    /**
     * 把仍为 INGESTING、但其 DOC_INGEST 作业已终态 FAILED/CANCELLED（含租约丢失 / worker 宕机 / 重试耗尽被回收）
     * 且没有仍在排队 / 运行的同来源作业的来源置 FAILED。sourceId 取自 ai_job.request_json。
     */
    @Update("""
            UPDATE doc_source s
            SET s.status = 2
            WHERE s.status = 0
              AND EXISTS (
                  SELECT 1 FROM ai_job j
                  WHERE j.kind = 'DOC_INGEST'
                    AND j.status IN (3, 4)
                    AND j.request_json REGEXP CONCAT('"sourceId"[[:space:]]*:[[:space:]]*', s.id, '([^0-9]|$)'))
              AND NOT EXISTS (
                  SELECT 1 FROM ai_job a
                  WHERE a.kind = 'DOC_INGEST'
                    AND a.status IN (0, 1)
                    AND a.request_json REGEXP CONCAT('"sourceId"[[:space:]]*:[[:space:]]*', s.id, '([^0-9]|$)'))
            """)
    int failIngestingWithTerminalJob();
}
