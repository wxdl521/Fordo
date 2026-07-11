package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.AiJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * AI 作业表 Mapper。领取走 {@code FOR UPDATE SKIP LOCKED}，恢复走批量 UPDATE。
 */
@Mapper
public interface AiJobMapper extends BaseMapper<AiJob> {

    /**
     * 领取一条 PENDING 作业（短事务内调用；SKIP LOCKED 保证多 worker 不互抢同一行）。
     */
    @Select("""
            SELECT * FROM ai_job
            WHERE status = 0
            ORDER BY created_at ASC, id ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    AiJob selectOnePendingForUpdate();

    /**
     * 将超时仍 RUNNING 且未用尽尝试次数的作业退回 PENDING。
     */
    @Update("""
            UPDATE ai_job
            SET status = 0,
                started_at = NULL
            WHERE status = 1
              AND started_at IS NOT NULL
              AND started_at < #{threshold}
              AND attempts < #{maxAttempts}
            """)
    int requeueStaleRunning(@Param("threshold") LocalDateTime threshold,
                            @Param("maxAttempts") int maxAttempts);

    /**
     * 超时 RUNNING 且 attempts 已达上限 → FAILED（稳定错误码）。
     */
    @Update("""
            UPDATE ai_job
            SET status = 3,
                error_code = 'STALE_EXHAUSTED',
                finished_at = #{now}
            WHERE status = 1
              AND started_at IS NOT NULL
              AND started_at < #{threshold}
              AND attempts >= #{maxAttempts}
            """)
    int failExhaustedStale(@Param("threshold") LocalDateTime threshold,
                           @Param("maxAttempts") int maxAttempts,
                           @Param("now") LocalDateTime now);
}
