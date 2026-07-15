package com.wenjin.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.AiJobVO;
import com.wenjin.entity.AiJob;
import com.wenjin.entity.Course;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.AiJobMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.AiJobService;
import com.wenjin.service.GraphExtractionService;
import com.wenjin.support.AiJobErrorCodes;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.AiJobStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("it")
class AiJobRecoveryIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("WENJIN_AUTH_SECRET", () -> "it-test-secret-not-for-prod-32-bytes");
        registry.add("wenjin.auth.secret", () -> "it-test-secret-not-for-prod-32-bytes");
        registry.add("wenjin.jobs.poll-interval-ms", () -> "3600000");
    }

    @Autowired private AiJobService aiJobService;
    @Autowired private AiJobMapper aiJobMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FeatureFlagsProperties featureFlags;
    @Autowired private CourseAccessService courseAccessService;
    @Autowired private ObjectMapper objectMapper;
    // This IT exercises jobs only; replace an unrelated legacy multi-constructor bean.
    @MockBean private GraphExtractionService graphExtractionService;

    private long teacherAId;
    private long teacherBId;
    private long courseAId;

    @BeforeEach
    void seed() {
        CurrentUser.clear();
        featureFlags.setAiJobDispatchEnabled(true);
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{"ai_job", "course", "sys_user"}) {
            jdbcTemplate.execute("DELETE FROM `" + table + "`");
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");

        SysUser teacherA = new SysUser();
        teacherA.setUsername("ai_job_teacher_a");
        teacherA.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        teacherA.setRole(1);
        teacherA.setStatus(1);
        sysUserMapper.insert(teacherA);
        teacherAId = teacherA.getId();

        SysUser teacherB = new SysUser();
        teacherB.setUsername("ai_job_teacher_b");
        teacherB.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        teacherB.setRole(1);
        teacherB.setStatus(1);
        sysUserMapper.insert(teacherB);
        teacherBId = teacherB.getId();

        Course course = new Course();
        course.setCode("AI_JOB_COURSE_A");
        course.setName("AI Job Course A");
        course.setTeacherId(teacherAId);
        course.setStatus(1);
        courseMapper.insert(course);
        courseAId = course.getId();
    }

    @AfterEach
    void clear() {
        CurrentUser.clear();
        featureFlags.setAiJobDispatchEnabled(true);
    }

    @Test
    @DisplayName("V7 exposes all durable lease columns and the status/lease_until index")
    void v7_hasDurableLeaseSchema() {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'ai_job'
                  AND column_name IN (
                    'cancel_requested_at', 'lease_token', 'lease_until',
                    'heartbeat_at', 'effect_started_at'
                  )
                """, String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "cancel_requested_at", "lease_token", "lease_until", "heartbeat_at", "effect_started_at");

        Map<String, Object> leaseTokenColumn = jdbcTemplate.queryForMap("""
                SELECT data_type, character_maximum_length, is_nullable
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'ai_job'
                  AND column_name = 'lease_token'
                """);
        assertThat(String.valueOf(leaseTokenColumn.get("data_type"))).isEqualToIgnoringCase("char");
        assertThat(((Number) leaseTokenColumn.get("character_maximum_length")).longValue()).isEqualTo(36L);
        assertThat(String.valueOf(leaseTokenColumn.get("is_nullable"))).isEqualToIgnoringCase("YES");
        Integer nullableDurableColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'ai_job'
                  AND is_nullable = 'YES'
                  AND column_name IN (
                    'cancel_requested_at', 'lease_token', 'lease_until',
                    'heartbeat_at', 'effect_started_at'
                  )
                """, Integer.class);
        assertThat(nullableDurableColumns).isEqualTo(5);

        String indexedColumns = jdbcTemplate.queryForObject("""
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'ai_job'
                  AND index_name = 'idx_ai_job_lease'
                """, String.class);
        assertThat(indexedColumns).isEqualTo("status,lease_until");
    }

    @Test
    @DisplayName("two workers racing one pending row create exactly one UUID lease")
    void twoWorkersClaimSameJob_onlyOneWins() throws Exception {
        AiJobVO created = dispatch("claim-race");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<AiJob> task = aiJobService::claimNext;
            Future<AiJob> first = pool.submit(task);
            Future<AiJob> second = pool.submit(task);
            List<AiJob> claimed = new ArrayList<>();
            AiJob firstResult = first.get(10, TimeUnit.SECONDS);
            AiJob secondResult = second.get(10, TimeUnit.SECONDS);
            if (firstResult != null) claimed.add(firstResult);
            if (secondResult != null) claimed.add(secondResult);

            assertThat(claimed).hasSize(1);
            AiJob lease = claimed.get(0);
            assertThat(lease.getId()).isEqualTo(created.getId());
            assertThat(lease.getStatus()).isEqualTo(AiJobStatus.RUNNING);
            assertThat(UUID.fromString(lease.getLeaseToken()).toString()).isEqualTo(lease.getLeaseToken());
            assertThat(lease.getLeaseUntil()).isAfter(lease.getHeartbeatAt());

            AiJob row = aiJobMapper.selectById(created.getId());
            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getLeaseToken()).isEqualTo(lease.getLeaseToken());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("heartbeat extends current ownership while an old token is rejected")
    void heartbeat_currentLeaseOnly() {
        AiJob claimed = claim("heartbeat");
        jdbcTemplate.update("UPDATE ai_job SET lease_until = ? WHERE id = ?",
                LocalDateTime.now().plusMinutes(1), claimed.getId());
        LocalDateTime shortened = aiJobMapper.selectById(claimed.getId()).getLeaseUntil();

        assertThat(aiJobService.heartbeat(claimed.getId(), "old-lease")).isFalse();
        assertThat(aiJobService.heartbeat(claimed.getId(), claimed.getLeaseToken())).isTrue();

        AiJob row = aiJobMapper.selectById(claimed.getId());
        assertThat(row.getLeaseUntil()).isAfter(shortened);
        assertThat(row.getHeartbeatAt()).isNotNull();
    }

    @Test
    @DisplayName("old workers cannot write after requeue and a fresh claim")
    void staleWorker_allWritesLoseToFreshLease() {
        AiJob firstLease = claim("lease-rollover");
        expire(firstLease.getId());
        assertThat(aiJobService.recoverStale()).isEqualTo(1);

        AiJob secondLease = aiJobService.claimNext();
        assertThat(secondLease).isNotNull();
        assertThat(secondLease.getLeaseToken()).isNotEqualTo(firstLease.getLeaseToken());

        String oldLease = firstLease.getLeaseToken();
        assertThat(aiJobService.markProgress(firstLease.getId(), oldLease, 30)).isFalse();
        assertThat(aiJobService.markEffectStarted(firstLease.getId(), oldLease)).isFalse();
        assertThat(aiJobService.markSucceeded(firstLease.getId(), oldLease, Map.of("stale", true))).isFalse();
        assertThat(aiJobService.markFailed(firstLease.getId(), oldLease, AiJobErrorCodes.AI_ERROR)).isFalse();
        assertThat(aiJobService.markCancelled(firstLease.getId(), oldLease)).isFalse();

        assertThat(aiJobService.markProgress(secondLease.getId(), secondLease.getLeaseToken(), 40)).isTrue();
        assertThat(aiJobService.markEffectStarted(secondLease.getId(), secondLease.getLeaseToken())).isTrue();
        assertThat(aiJobService.markSucceeded(
                secondLease.getId(), secondLease.getLeaseToken(), Map.of("fresh", true))).isTrue();
        assertThat(aiJobMapper.selectById(secondLease.getId()).getStatus()).isEqualTo(AiJobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("running cancellation is durable and visible to another service instance")
    void runningCancel_isVisibleAcrossServiceInstances() {
        AiJob claimed = claim("running-cancel");
        AiJobService otherInstance = new AiJobService(
                aiJobMapper, courseAccessService, featureFlags, objectMapper);

        AiJobVO response = otherInstance.cancel(claimed.getId());

        assertThat(response.getStatus()).isEqualTo(AiJobStatus.RUNNING);
        assertThat(aiJobMapper.selectById(claimed.getId()).getCancelRequestedAt()).isNotNull();
        assertThat(aiJobService.isCancelRequested(claimed.getId(), claimed.getLeaseToken())).isTrue();
        assertThat(otherInstance.isCancelRequested(claimed.getId(), claimed.getLeaseToken())).isTrue();
        assertThat(aiJobService.isCancelRequested(claimed.getId(), "old-lease")).isFalse();
        assertThat(aiJobService.markCancelled(claimed.getId(), claimed.getLeaseToken())).isTrue();
        assertThat(aiJobMapper.selectById(claimed.getId()).getStatus()).isEqualTo(AiJobStatus.CANCELLED);
    }

    @Test
    @DisplayName("pending cancellation persists intent and completes with a status CAS")
    void pendingCancel_isImmediateAndDurable() {
        AiJobVO created = dispatch("pending-cancel");

        AiJobVO response = aiJobService.cancel(created.getId());

        assertThat(response.getStatus()).isEqualTo(AiJobStatus.CANCELLED);
        AiJob row = aiJobMapper.selectById(created.getId());
        assertThat(row.getStatus()).isEqualTo(AiJobStatus.CANCELLED);
        assertThat(row.getCancelRequestedAt()).isNotNull();
        assertThat(row.getErrorCode()).isEqualTo(AiJobErrorCodes.CANCELLED);
    }

    @Test
    @DisplayName("claim and pending cancel overlap without losing durable cancellation")
    void pendingCancelRace_withClaim_persistsIntentForEitherWinner() throws Exception {
        AiJobVO created = dispatch("pending-cancel-race");
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AiJob> claim = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return aiJobService.claimNext();
            });
            Future<AiJobVO> cancel = pool.submit(() -> {
                CurrentUser.set(new CurrentPrincipal(teacherAId, UserRole.TEACHER));
                try {
                    start.await(10, TimeUnit.SECONDS);
                    return aiJobService.cancel(created.getId());
                } finally {
                    CurrentUser.clear();
                }
            });

            AiJob claimed = claim.get(10, TimeUnit.SECONDS);
            cancel.get(10, TimeUnit.SECONDS);
            AiJob row = aiJobMapper.selectById(created.getId());

            assertThat(row.getStatus()).isIn(AiJobStatus.CANCELLED, AiJobStatus.RUNNING);
            assertThat(row.getCancelRequestedAt()).isNotNull();
            if (row.getStatus() == AiJobStatus.RUNNING) {
                assertThat(claimed).isNotNull();
                assertThat(row.getLeaseToken()).isEqualTo(claimed.getLeaseToken());
                assertThat(aiJobService.isCancelRequested(row.getId(), row.getLeaseToken())).isTrue();
            } else {
                assertThat(claimed).isNull();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("expired recovery preserves cancel intent and never replays effect-started work")
    void expiredRecovery_isMutuallyExclusiveAndEffectSafe() {
        LocalDateTime expired = LocalDateTime.now().minusMinutes(1);
        AiJob requeue = insertRunning("stale-requeue", 1, expired, null, LocalDateTime.now().minusMinutes(2));
        AiJob exhausted = insertRunning("stale-exhausted", AiJobService.MAX_ATTEMPTS, expired, null, null);
        AiJob unknown = insertRunning(
                "stale-effect", 1, expired, LocalDateTime.now().minusMinutes(2), null);

        assertThat(aiJobService.recoverStale()).isEqualTo(3);

        AiJob requeuedRow = aiJobMapper.selectById(requeue.getId());
        assertThat(requeuedRow.getStatus()).isEqualTo(AiJobStatus.PENDING);
        assertThat(requeuedRow.getLeaseToken()).isNull();
        assertThat(requeuedRow.getLeaseUntil()).isNull();
        assertThat(requeuedRow.getHeartbeatAt()).isNull();
        assertThat(requeuedRow.getCancelRequestedAt()).isNotNull();
        assertThat(requeuedRow.getAttempts()).isEqualTo(1);

        AiJob exhaustedRow = aiJobMapper.selectById(exhausted.getId());
        assertThat(exhaustedRow.getStatus()).isEqualTo(AiJobStatus.FAILED);
        assertThat(exhaustedRow.getErrorCode()).isEqualTo(AiJobErrorCodes.STALE_EXHAUSTED);

        AiJob unknownRow = aiJobMapper.selectById(unknown.getId());
        assertThat(unknownRow.getStatus()).isEqualTo(AiJobStatus.FAILED);
        assertThat(unknownRow.getErrorCode()).isEqualTo(AiJobErrorCodes.UNKNOWN_OUTCOME);
        assertThat(aiJobService.recoverStale()).isZero();
        assertThat(aiJobMapper.selectById(unknown.getId()).getStatus()).isEqualTo(AiJobStatus.FAILED);
    }

    @Test
    @DisplayName("concurrent terminal CAS allows exactly one winner")
    void concurrentTerminalWrites_exactlyOneWins() throws Exception {
        AiJob claimed = claim("terminal-race");
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> success = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return aiJobService.markSucceeded(
                        claimed.getId(), claimed.getLeaseToken(), Map.of("ok", true));
            });
            Future<Boolean> failure = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return aiJobService.markFailed(
                        claimed.getId(), claimed.getLeaseToken(), AiJobErrorCodes.AI_ERROR);
            });

            assertThat(List.of(success.get(10, TimeUnit.SECONDS), failure.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(aiJobMapper.selectById(claimed.getId()).getStatus())
                    .isIn(AiJobStatus.SUCCEEDED, AiJobStatus.FAILED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("same idempotency key returns the original job")
    void sameIdempotencyKey_returnsOriginal() {
        AiJobVO first = dispatch("idem-1");
        AiJobVO second = dispatch("idem-1");
        assertThat(second.getId()).isEqualTo(first.getId());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_job WHERE owner_id=? AND request_key='idem-1'",
                Integer.class, teacherAId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("owner can read while a different teacher receives 404")
    void ownerRead_crossTeacher404() {
        AiJobVO job = dispatch("own-1");
        assertThat(aiJobService.getForOwner(job.getId()).getId()).isEqualTo(job.getId());

        CurrentUser.set(new CurrentPrincipal(teacherBId, UserRole.TEACHER));
        assertThatThrownBy(() -> aiJobService.getForOwner(job.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("dispatch feature flag off returns 503")
    void featureFlagOff_dispatch503() {
        featureFlags.setAiJobDispatchEnabled(false);
        CurrentUser.set(new CurrentPrincipal(teacherAId, UserRole.TEACHER));
        assertThatThrownBy(() -> aiJobService.dispatch(courseAId, AiJobKind.QUESTION_GENERATE, "off-1",
                Map.of("nodeCode", "KT01", "count", 1)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
    }

    @Test
    @DisplayName("payload hash is eligible only when every reference is terminal and older than cutoff")
    void payloadCleanupEligibility_requiresAllReferencesOldAndTerminal() {
        String eligible = "a".repeat(64);
        String active = "b".repeat(64);
        String recentTerminal = "c".repeat(64);
        String missingFinishedAt = "d".repeat(64);
        String running = "e".repeat(64);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        LocalDateTime old = cutoff.minusDays(1);
        LocalDateTime recent = cutoff.plusDays(1);

        insertPayloadReference("eligible-success", eligible, AiJobStatus.SUCCEEDED, old);
        insertPayloadReference("eligible-failed", eligible, AiJobStatus.FAILED, old);
        insertPayloadReference("eligible-cancelled", eligible, AiJobStatus.CANCELLED, old);
        insertPayloadReference("active-old", active, AiJobStatus.SUCCEEDED, old);
        insertPayloadReference("active-pending", active, AiJobStatus.PENDING, null);
        insertPayloadReference("active-running", running, AiJobStatus.RUNNING, null);
        insertPayloadReference("recent-old", recentTerminal, AiJobStatus.SUCCEEDED, old);
        insertPayloadReference("recent-new", recentTerminal, AiJobStatus.FAILED, recent);
        insertPayloadReference("missing-finished", missingFinishedAt, AiJobStatus.SUCCEEDED, null);

        assertThat(aiJobMapper.selectPayloadHashesEligibleForDeletion(cutoff, 100))
                .containsExactly(eligible);
    }

    @Test
    @DisplayName("payload cleanup queries ignore malformed or noncanonical hashes and honor batch limit")
    void payloadCleanupQueries_ignoreMalformedJsonAndHonorLimit() {
        String first = "a".repeat(64);
        String second = "b".repeat(64);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        LocalDateTime old = cutoff.minusDays(1);
        AiJob firstJob = insertPayloadReference(
                "cleanup-first", first, AiJobStatus.SUCCEEDED, old);
        AiJob secondJob = insertPayloadReference(
                "cleanup-second", second, AiJobStatus.FAILED, old);
        insertPayloadReference("cleanup-uppercase", "C".repeat(64), AiJobStatus.SUCCEEDED, old);
        AiJob malformedJob = insertRawRequest(
                "cleanup-malformed", "{not-json", AiJobStatus.SUCCEEDED, old);

        assertThat(aiJobMapper.selectPayloadHashesEligibleForDeletion(cutoff, 1))
                .containsExactly(first);
        assertThat(aiJobMapper.clearPayloadReferencesAfterDeletion(first, cutoff)).isEqualTo(1);
        assertThat(aiJobMapper.selectPayloadHashesEligibleForDeletion(cutoff, 1))
                .containsExactly(second);
        assertThat(aiJobMapper.selectAllReferencedPayloadHashes())
                .containsExactly(second);
        assertThat(aiJobMapper.countPayloadReferences(first)).isZero();
        assertThat(aiJobMapper.countPayloadReferences("f".repeat(64))).isZero();
        assertThat(aiJobMapper.countJobPayloadReference(firstJob.getId(), first)).isZero();
        assertThat(aiJobMapper.countJobPayloadReference(secondJob.getId(), second)).isEqualTo(1);
        assertThat(aiJobMapper.countJobPayloadReference(malformedJob.getId(), second)).isZero();
    }

    private AiJobVO dispatch(String key) {
        CurrentUser.set(new CurrentPrincipal(teacherAId, UserRole.TEACHER));
        return aiJobService.dispatch(courseAId, AiJobKind.QUESTION_GENERATE, key,
                Map.of("nodeCode", "KT01", "count", 1));
    }

    private AiJob insertPayloadReference(String key,
                                         String fileHash,
                                         int status,
                                         LocalDateTime finishedAt) {
        AiJob job = new AiJob();
        job.setKind(AiJobKind.QUESTION_IMPORT_EXCEL);
        job.setOwnerId(teacherAId);
        job.setCourseId(courseAId);
        job.setRequestKey(key);
        job.setStatus(status);
        job.setProgress(AiJobStatus.isTerminal(status) ? 100 : 0);
        job.setAttempts(0);
        job.setRequestJson("{\"fileHash\":\"" + fileHash + "\"}");
        job.setCreatedAt(LocalDateTime.now().minusDays(10));
        job.setFinishedAt(finishedAt);
        aiJobMapper.insert(job);
        return job;
    }

    private AiJob insertRawRequest(String key,
                                   String requestJson,
                                   int status,
                                   LocalDateTime finishedAt) {
        AiJob job = new AiJob();
        job.setKind(AiJobKind.QUESTION_IMPORT_EXCEL);
        job.setOwnerId(teacherAId);
        job.setCourseId(courseAId);
        job.setRequestKey(key);
        job.setStatus(status);
        job.setProgress(100);
        job.setAttempts(0);
        job.setRequestJson(requestJson);
        job.setCreatedAt(LocalDateTime.now().minusDays(10));
        job.setFinishedAt(finishedAt);
        aiJobMapper.insert(job);
        return job;
    }

    private AiJob claim(String key) {
        AiJobVO created = dispatch(key);
        AiJob claimed = aiJobService.claimNext();
        assertThat(claimed).isNotNull();
        assertThat(claimed.getId()).isEqualTo(created.getId());
        return claimed;
    }

    private void expire(Long jobId) {
        jdbcTemplate.update("UPDATE ai_job SET lease_until = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), jobId);
    }

    private AiJob insertRunning(String key,
                                int attempts,
                                LocalDateTime leaseUntil,
                                LocalDateTime effectStartedAt,
                                LocalDateTime cancelRequestedAt) {
        AiJob job = new AiJob();
        job.setKind(AiJobKind.QUESTION_GENERATE);
        job.setOwnerId(teacherAId);
        job.setCourseId(courseAId);
        job.setRequestKey(key);
        job.setStatus(AiJobStatus.RUNNING);
        job.setProgress(10);
        job.setAttempts(attempts);
        job.setRequestJson("{\"nodeCode\":\"KT01\",\"count\":1}");
        job.setCreatedAt(LocalDateTime.now().minusHours(1));
        job.setStartedAt(LocalDateTime.now().minusMinutes(20));
        job.setLeaseToken(UUID.randomUUID().toString());
        job.setLeaseUntil(leaseUntil);
        job.setHeartbeatAt(LocalDateTime.now().minusMinutes(2));
        job.setEffectStartedAt(effectStartedAt);
        job.setCancelRequestedAt(cancelRequestedAt);
        aiJobMapper.insert(job);
        return job;
    }
}
