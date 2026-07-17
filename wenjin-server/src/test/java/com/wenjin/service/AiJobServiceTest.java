package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.AiJobVO;
import com.wenjin.entity.AiJob;
import com.wenjin.mapper.AiJobMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.support.AiJobErrorCodes;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.AiJobStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiJobServiceTest {

    @Mock AiJobMapper aiJobMapper;
    @Mock CourseAccessService courseAccessService;

    private FeatureFlagsProperties featureFlags;
    private AiJobService service;
    private final AtomicLong idSeq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        featureFlags = new FeatureFlagsProperties();
        featureFlags.setAiJobDispatchEnabled(true);
        service = new AiJobService(aiJobMapper, courseAccessService, featureFlags, new ObjectMapper());
        doNothing().when(courseAccessService).assertManageCourse(any());
        when(aiJobMapper.insert(any(AiJob.class))).thenAnswer(inv -> {
            AiJob job = inv.getArgument(0);
            job.setId(idSeq.getAndIncrement());
            return 1;
        });
        CurrentUser.set(new CurrentPrincipal(10L, UserRole.TEACHER));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("each claim creates a fresh UUID lease with heartbeat and bounded expiry")
    void claim_createsFreshLeaseOwnership() {
        AiJob firstPending = pendingJob(1L, 10L);
        AiJob secondPending = pendingJob(2L, 10L);
        when(aiJobMapper.selectOnePendingForUpdate())
                .thenReturn(firstPending)
                .thenReturn(secondPending)
                .thenReturn(null);
        when(aiJobMapper.claimPending(any(), anyString(), any(), any(), eq(AiJobService.MAX_ATTEMPTS)))
                .thenReturn(1);

        LocalDateTime before = LocalDateTime.now();
        AiJob first = service.claimNext();
        AiJob second = service.claimNext();
        AiJob none = service.claimNext();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(none).isNull();
        assertThat(first.getStatus()).isEqualTo(AiJobStatus.RUNNING);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(UUID.fromString(first.getLeaseToken()).toString()).isEqualTo(first.getLeaseToken());
        assertThat(UUID.fromString(second.getLeaseToken()).toString()).isEqualTo(second.getLeaseToken());
        assertThat(second.getLeaseToken()).isNotEqualTo(first.getLeaseToken());
        assertThat(first.getHeartbeatAt()).isBetween(before, LocalDateTime.now());
        assertThat(first.getLeaseUntil()).isAfter(first.getHeartbeatAt().plusMinutes(AiJobService.LEASE_MINUTES - 1));
        verify(aiJobMapper, never()).updateById(any(AiJob.class));
    }

    @Test
    @DisplayName("a pending row already at max attempts is failed atomically and not claimed")
    void claim_whenMaxAttempts_marksFailedAtomically() {
        AiJob job = pendingJob(3L, 10L);
        job.setAttempts(AiJobService.MAX_ATTEMPTS);
        when(aiJobMapper.selectOnePendingForUpdate()).thenReturn(job);
        when(aiJobMapper.failPendingMaxAttempts(eq(3L), eq(AiJobService.MAX_ATTEMPTS), any()))
                .thenReturn(1);

        assertThat(service.claimNext()).isNull();

        verify(aiJobMapper).failPendingMaxAttempts(eq(3L), eq(AiJobService.MAX_ATTEMPTS), any());
        verify(aiJobMapper, never()).updateById(any(AiJob.class));
    }

    @Test
    @DisplayName("recovery orders mutually exclusive effect, exhausted, then requeue updates")
    void recoverStale_runsSafetyRulesInOrder() {
        when(aiJobMapper.failExpiredEffectStarted(any())).thenReturn(1);
        when(aiJobMapper.failExpiredPreEffectExhausted(eq(AiJobService.MAX_ATTEMPTS), any())).thenReturn(2);
        when(aiJobMapper.requeueExpiredPreEffect(eq(AiJobService.MAX_ATTEMPTS), any())).thenReturn(3);

        assertThat(service.recoverStale()).isEqualTo(6);

        InOrder order = inOrder(aiJobMapper);
        order.verify(aiJobMapper).failExpiredEffectStarted(any(LocalDateTime.class));
        order.verify(aiJobMapper).failExpiredPreEffectExhausted(
                eq(AiJobService.MAX_ATTEMPTS), any(LocalDateTime.class));
        order.verify(aiJobMapper).requeueExpiredPreEffect(
                eq(AiJobService.MAX_ATTEMPTS), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("heartbeat extends only the current lease and reports lease loss")
    void heartbeat_currentLeaseExtends_oldLeaseFails() {
        when(aiJobMapper.heartbeat(eq(8L), eq("current"), any(), any())).thenReturn(1);

        assertThat(service.heartbeat(8L, "current")).isTrue();
        assertThat(service.heartbeat(8L, "old")).isFalse();

        ArgumentCaptor<LocalDateTime> heartbeatAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> leaseUntil = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(aiJobMapper).heartbeat(eq(8L), eq("current"), heartbeatAt.capture(), leaseUntil.capture());
        assertThat(leaseUntil.getValue())
                .isAfter(heartbeatAt.getValue().plusMinutes(AiJobService.LEASE_MINUTES - 1));
    }

    @Test
    @DisplayName("every worker transition is lease-qualified and returns its CAS result")
    void workerTransitions_areLeaseQualifiedBooleanCas() {
        when(aiJobMapper.updateProgress(9L, "current", 50)).thenReturn(1);
        when(aiJobMapper.markEffectStarted(eq(9L), eq("current"), any())).thenReturn(1);
        when(aiJobMapper.markSucceeded(eq(9L), eq("current"), anyString(), any())).thenReturn(1);
        when(aiJobMapper.markFailed(eq(9L), eq("current"), eq(AiJobErrorCodes.AI_ERROR), any())).thenReturn(1);
        when(aiJobMapper.markCancelled(eq(9L), eq("current"), any())).thenReturn(1);

        assertThat(service.markProgress(9L, "old", 50)).isFalse();
        assertThat(service.markProgress(9L, "current", 50)).isTrue();
        assertThat(service.markEffectStarted(9L, "old")).isFalse();
        assertThat(service.markEffectStarted(9L, "current")).isTrue();
        assertThat(service.markSucceeded(9L, "old", Map.of("ok", true))).isFalse();
        assertThat(service.markSucceeded(9L, "current", Map.of("ok", true))).isTrue();
        assertThat(service.markFailed(9L, "old", AiJobErrorCodes.AI_ERROR)).isFalse();
        assertThat(service.markFailed(9L, "current", AiJobErrorCodes.AI_ERROR)).isTrue();
        assertThat(service.markCancelled(9L, "old")).isFalse();
        assertThat(service.markCancelled(9L, "current")).isTrue();

        // The two adjacent String arguments are deliberately captured to prevent token/code reversal.
        ArgumentCaptor<String> leaseToken = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorCode = ArgumentCaptor.forClass(String.class);
        verify(aiJobMapper, times(2))
                .markFailed(eq(9L), leaseToken.capture(), errorCode.capture(), any());
        assertThat(leaseToken.getAllValues()).containsExactly("old", "current");
        assertThat(errorCode.getAllValues())
                .containsExactly(AiJobErrorCodes.AI_ERROR, AiJobErrorCodes.AI_ERROR);
        verify(aiJobMapper, never()).updateById(any(AiJob.class));
    }

    @Test
    @DisplayName("cancel handles a pending-to-running claim race with a durable request")
    void cancel_claimWinsPendingCas_requestsRunningCancel() {
        AiJob pending = pendingJob(11L, 10L);
        AiJob running = pendingJob(11L, 10L);
        running.setStatus(AiJobStatus.RUNNING);
        running.setLeaseToken(UUID.randomUUID().toString());
        running.setCancelRequestedAt(LocalDateTime.now());
        when(aiJobMapper.selectById(11L)).thenReturn(pending, running);
        when(aiJobMapper.cancelPending(eq(11L), any())).thenReturn(0);
        when(aiJobMapper.requestRunningCancel(eq(11L), any())).thenReturn(1);

        AiJobVO response = service.cancel(11L);

        assertThat(response.getStatus()).isEqualTo(AiJobStatus.RUNNING);
        verify(aiJobMapper).cancelPending(eq(11L), any());
        verify(aiJobMapper).requestRunningCancel(eq(11L), any());
        verify(aiJobMapper, never()).updateById(any(AiJob.class));
    }

    @Test
    @DisplayName("cancel and current-lease checks are read from durable mapper state")
    void cancellationAndLeaseChecks_useDatabaseState() {
        AiJobService otherInstance = new AiJobService(
                aiJobMapper, courseAccessService, featureFlags, new ObjectMapper());
        when(aiJobMapper.countCancelRequestedForLease(12L, "current")).thenReturn(1);
        when(aiJobMapper.countCurrentLease(12L, "current")).thenReturn(1);

        assertThat(service.isCancelRequested(12L, "current")).isTrue();
        assertThat(otherInstance.isCancelRequested(12L, "current")).isTrue();
        assertThat(service.isCancelRequested(12L, "old")).isFalse();
        assertThat(service.isCurrentLease(12L, "current")).isTrue();
        assertThat(service.isCurrentLease(12L, "old")).isFalse();
    }

    @Test
    @DisplayName("same idempotency key returns the original job")
    void sameIdempotencyKey_returnsOriginalJob() {
        AiJob existing = pendingJob(7L, 10L);
        existing.setRequestKey("key-abc");
        existing.setKind(AiJobKind.QUESTION_GENERATE);
        when(aiJobMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        AiJobVO first = service.dispatch(1L, AiJobKind.QUESTION_GENERATE, "key-abc",
                Map.of("nodeCode", "KT07", "count", 3));
        AiJobVO second = service.dispatch(1L, AiJobKind.QUESTION_GENERATE, "key-abc",
                Map.of("nodeCode", "KT07", "count", 3));

        assertThat(first.getId()).isEqualTo(7L);
        assertThat(second.getId()).isEqualTo(7L);
        verify(aiJobMapper, never()).insert(any(AiJob.class));
    }

    @Test
    @DisplayName("owner can read own job while cross-teacher access is 404")
    void ownerCanRead_crossTeacher404() {
        AiJob job = pendingJob(5L, 10L);
        when(aiJobMapper.selectById(5L)).thenReturn(job);
        assertThat(service.getForOwner(5L).getId()).isEqualTo(5L);

        CurrentUser.set(new CurrentPrincipal(99L, UserRole.TEACHER));
        assertThatThrownBy(() -> service.getForOwner(5L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("dispatch feature flag off returns 503")
    void featureFlagOff_dispatch503() {
        featureFlags.setAiJobDispatchEnabled(false);
        assertThatThrownBy(() -> service.dispatch(1L, AiJobKind.QUESTION_GENERATE, "k", Map.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        verify(aiJobMapper, never()).insert(any(AiJob.class));
    }

    private static AiJob pendingJob(Long id, Long ownerId) {
        AiJob job = new AiJob();
        job.setId(id);
        job.setOwnerId(ownerId);
        job.setCourseId(1L);
        job.setKind(AiJobKind.QUESTION_GENERATE);
        job.setRequestKey("rk-" + id);
        job.setStatus(AiJobStatus.PENDING);
        job.setProgress(0);
        job.setAttempts(0);
        job.setRequestJson("{\"nodeCode\":\"KT07\",\"count\":1}");
        job.setCreatedAt(LocalDateTime.now());
        return job;
    }
}
