package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.AiJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** Atomic queue, lease, cancellation, and terminal transitions for AI jobs. */
@Mapper
public interface AiJobMapper extends BaseMapper<AiJob> {

    @Select("""
            SELECT * FROM ai_job
            WHERE status = 0
            ORDER BY created_at ASC, id ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    AiJob selectOnePendingForUpdate();

    @Update("""
            UPDATE ai_job
            SET status = 1,
                attempts = attempts + 1,
                progress = 0,
                result_json = NULL,
                error_code = NULL,
                started_at = #{now},
                finished_at = NULL,
                lease_token = #{leaseToken},
                lease_until = #{leaseUntil},
                heartbeat_at = #{now},
                effect_started_at = NULL
            WHERE id = #{jobId}
              AND status = 0
              AND attempts < #{maxAttempts}
            """)
    int claimPending(@Param("jobId") Long jobId,
                     @Param("leaseToken") String leaseToken,
                     @Param("now") LocalDateTime now,
                     @Param("leaseUntil") LocalDateTime leaseUntil,
                     @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE ai_job
            SET status = 3,
                error_code = 'MAX_ATTEMPTS',
                finished_at = #{now},
                lease_token = NULL,
                lease_until = NULL,
                heartbeat_at = NULL
            WHERE id = #{jobId}
              AND status = 0
              AND attempts >= #{maxAttempts}
            """)
    int failPendingMaxAttempts(@Param("jobId") Long jobId,
                               @Param("maxAttempts") int maxAttempts,
                               @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_job
            SET status = 4,
                error_code = 'CANCELLED',
                cancel_requested_at = COALESCE(cancel_requested_at, #{now}),
                finished_at = #{now},
                lease_token = NULL,
                lease_until = NULL,
                heartbeat_at = NULL
            WHERE id = #{jobId}
              AND status = 0
            """)
    int cancelPending(@Param("jobId") Long jobId,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_job
            SET cancel_requested_at = COALESCE(cancel_requested_at, #{now})
            WHERE id = #{jobId}
              AND status = 1
            """)
    int requestRunningCancel(@Param("jobId") Long jobId,
                             @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*)
            FROM ai_job
            WHERE id = #{jobId}
              AND JSON_TYPE(JSON_EXTRACT(
                    IF(JSON_VALID(request_json), request_json, '{}'), '$.fileHash')) = 'STRING'
              AND JSON_UNQUOTE(JSON_EXTRACT(
                    IF(JSON_VALID(request_json), request_json, '{}'), '$.fileHash')) = #{fileHash}
            """)
    int countJobPayloadReference(@Param("jobId") Long jobId,
                                 @Param("fileHash") String fileHash);

    @Select("""
            SELECT COUNT(*)
            FROM ai_job
            WHERE id = #{jobId}
              AND status = 1
              AND lease_token = #{leaseToken}
            """)
    int countCurrentLease(@Param("jobId") Long jobId,
                          @Param("leaseToken") String leaseToken);

    @Select("""
            SELECT COUNT(*)
            FROM ai_job
            WHERE id = #{jobId}
              AND status = 1
              AND lease_token = #{leaseToken}
              AND cancel_requested_at IS NOT NULL
            """)
    int countCancelRequestedForLease(@Param("jobId") Long jobId,
                                     @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE ai_job
            SET heartbeat_at = #{now},
                lease_until = #{leaseUntil}
            WHERE id = #{jobId}
              AND status = 1
              AND lease_token = #{leaseToken}
            """)
    int heartbeat(@Param("jobId") Long jobId,
                  @Param("leaseToken") String leaseToken,
                  @Param("now") LocalDateTime now,
                  @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE ai_job
            SET progress = #{progress}
            WHERE id = #{jobId}
              AND status = 1
              AND lease_token = #{leaseToken}
            """)
    int updateProgress(@Param("jobId") Long jobId,
                       @Param("leaseToken") String leaseToken,
                       @Param("progress") int progress);

    @Update("""
            UPDATE ai_job
            SET effect_started_at = COALESCE(effect_started_at, #{now})
            WHERE id = #{jobId}
              AND status = 1
              AND lease_token = #{leaseToken}
            """)
    int markEffectStarted(@Param("jobId") Long jobId,
                          @Param("leaseToken") String leaseToken,
                          @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_job
            SET status = 2,
                progress = 100,
                result_json = #{resultJson},
                error_code = NULL,
                finished_at = #{now},
                lease_token = NULL,
                lease_until = NULL,
                heartbeat_at = NULL
            WHERE id = #{jobId}
              AND status = 1
              AND lease_token = #{leaseToken}
            """)
    int markSucceeded(@Param("jobId") Long jobId,
                      @Param("leaseToken") String leaseToken,
                      @Param("resultJson") String resultJson,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_job
            SET status = 3,
                error_code = #{errorCode},
                finished_at = #{now},
                lease_token = NULL,
                lease_until = NULL,
                heartbeat_at = NULL
            WHERE id = #{jobId}
              AND status = 1
              AND lease_token = #{leaseToken}
            """)
    int markFailed(@Param("jobId") Long jobId,
                   @Param("leaseToken") String leaseToken,
                   @Param("errorCode") String errorCode,
                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_job
            SET status = 4,
                error_code = 'CANCELLED',
                cancel_requested_at = COALESCE(cancel_requested_at, #{now}),
                finished_at = #{now},
                lease_token = NULL,
                lease_until = NULL,
                heartbeat_at = NULL
            WHERE id = #{jobId}
              AND status = 1
              AND lease_token = #{leaseToken}
            """)
    int markCancelled(@Param("jobId") Long jobId,
                      @Param("leaseToken") String leaseToken,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_job
            SET status = 3,
                error_code = 'UNKNOWN_OUTCOME',
                finished_at = #{now},
                lease_token = NULL,
                lease_until = NULL,
                heartbeat_at = NULL
            WHERE status = 1
              AND lease_until IS NOT NULL
              AND lease_until < #{now}
              AND effect_started_at IS NOT NULL
            """)
    int failExpiredEffectStarted(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_job
            SET status = 3,
                error_code = 'STALE_EXHAUSTED',
                finished_at = #{now},
                lease_token = NULL,
                lease_until = NULL,
                heartbeat_at = NULL
            WHERE status = 1
              AND lease_until IS NOT NULL
              AND lease_until < #{now}
              AND effect_started_at IS NULL
              AND attempts >= #{maxAttempts}
            """)
    int failExpiredPreEffectExhausted(@Param("maxAttempts") int maxAttempts,
                                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_job
            SET status = 0,
                progress = 0,
                result_json = NULL,
                error_code = NULL,
                started_at = NULL,
                finished_at = NULL,
                lease_token = NULL,
                lease_until = NULL,
                heartbeat_at = NULL,
                effect_started_at = NULL
            WHERE status = 1
              AND lease_until IS NOT NULL
              AND lease_until < #{now}
              AND effect_started_at IS NULL
              AND attempts < #{maxAttempts}
            """)
    int requeueExpiredPreEffect(@Param("maxAttempts") int maxAttempts,
                                @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*)
            FROM ai_job
            WHERE JSON_TYPE(JSON_EXTRACT(
                    IF(JSON_VALID(request_json), request_json, '{}'), '$.fileHash')) = 'STRING'
              AND JSON_UNQUOTE(JSON_EXTRACT(
                    IF(JSON_VALID(request_json), request_json, '{}'), '$.fileHash')) = #{fileHash}
            """)
    int countPayloadReferences(@Param("fileHash") String fileHash);

    @Update("""
            UPDATE ai_job
            SET request_json = JSON_REMOVE(
                    IF(JSON_VALID(request_json), request_json, '{}'), '$.fileHash')
            WHERE status IN (2, 3, 4)
              AND finished_at IS NOT NULL
              AND finished_at < #{before}
              AND JSON_TYPE(JSON_EXTRACT(
                    IF(JSON_VALID(request_json), request_json, '{}'), '$.fileHash')) = 'STRING'
              AND JSON_UNQUOTE(JSON_EXTRACT(
                    IF(JSON_VALID(request_json), request_json, '{}'), '$.fileHash')) = #{fileHash}
            """)
    int clearPayloadReferencesAfterDeletion(@Param("fileHash") String fileHash,
                                             @Param("before") LocalDateTime before);

    @Select("""
            SELECT DISTINCT JSON_UNQUOTE(JSON_EXTRACT(
                    IF(JSON_VALID(request_json), request_json, '{}'), '$.fileHash')) AS file_hash
            FROM ai_job
            WHERE JSON_TYPE(JSON_EXTRACT(
                    IF(JSON_VALID(request_json), request_json, '{}'), '$.fileHash')) = 'STRING'
              AND JSON_UNQUOTE(JSON_EXTRACT(
                    IF(JSON_VALID(request_json), request_json, '{}'), '$.fileHash'))
                    REGEXP BINARY '^[a-f0-9]{64}$'
            ORDER BY file_hash
            """)
    List<String> selectAllReferencedPayloadHashes();

    @Select("""
            SELECT JSON_UNQUOTE(JSON_EXTRACT(
                    IF(JSON_VALID(request_json), request_json, '{}'), '$.fileHash')) AS file_hash
            FROM ai_job
            WHERE JSON_TYPE(JSON_EXTRACT(
                    IF(JSON_VALID(request_json), request_json, '{}'), '$.fileHash')) = 'STRING'
              AND JSON_UNQUOTE(JSON_EXTRACT(
                    IF(JSON_VALID(request_json), request_json, '{}'), '$.fileHash'))
                    REGEXP BINARY '^[a-f0-9]{64}$'
            GROUP BY file_hash
            HAVING SUM(CASE WHEN status NOT IN (2, 3, 4) THEN 1 ELSE 0 END) = 0
               AND SUM(CASE
                         WHEN finished_at IS NULL OR finished_at >= #{before} THEN 1
                         ELSE 0
                       END) = 0
            ORDER BY file_hash
            LIMIT #{limit}
            """)
    List<String> selectPayloadHashesEligibleForDeletion(@Param("before") LocalDateTime before,
                                                         @Param("limit") int limit);
}
