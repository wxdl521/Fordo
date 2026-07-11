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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiJobService 单元测试：幂等键、领取互斥、卡住恢复、所有权 404、特性开关 503。
 */
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
            AiJob j = inv.getArgument(0);
            j.setId(idSeq.getAndIncrement());
            return 1;
        });
        CurrentUser.set(new CurrentPrincipal(10L, UserRole.TEACHER));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("两 worker 领取：仅第一个拿到 RUNNING，第二个拿不到同一 PENDING")
    void twoWorkersClaim_onlyOneWins() {
        AiJob pending = pendingJob(1L, 10L);

        // 第一次 claim 看到 PENDING；第二次队列空
        when(aiJobMapper.selectOnePendingForUpdate())
                .thenReturn(pending)
                .thenReturn(null);

        AiJob first = service.claimNext();
        AiJob second = service.claimNext();

        assertThat(first).isNotNull();
        assertThat(first.getStatus()).isEqualTo(AiJobStatus.RUNNING);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(second).isNull();

        ArgumentCaptor<AiJob> captor = ArgumentCaptor.forClass(AiJob.class);
        verify(aiJobMapper, times(1)).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AiJobStatus.RUNNING);
    }

    @Test
    @DisplayName("同一 Idempotency-Key 返回原作业，不重复 insert")
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
        assertThat(first.getRequestKey()).isEqualTo("key-abc");
        verify(aiJobMapper, never()).insert(any(AiJob.class));
    }

    @Test
    @DisplayName("卡住 RUNNING>15min 且 attempts<3 → requeue；attempts≥3 → fail")
    void recoverStale_requeueOrFailByAttempts() {
        when(aiJobMapper.failExhaustedStale(any(), any(Integer.class), any())).thenReturn(1);
        when(aiJobMapper.requeueStaleRunning(any(), any(Integer.class))).thenReturn(2);

        int n = service.recoverStale();
        assertThat(n).isEqualTo(3);

        verify(aiJobMapper).failExhaustedStale(any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(AiJobService.MAX_ATTEMPTS), any(LocalDateTime.class));
        verify(aiJobMapper).requeueStaleRunning(any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(AiJobService.MAX_ATTEMPTS));
    }

    @Test
    @DisplayName("claim 时 attempts 已达上限 → FAILED(MAX_ATTEMPTS)，不返回执行")
    void claim_whenMaxAttempts_marksFailed() {
        AiJob job = pendingJob(3L, 10L);
        job.setAttempts(AiJobService.MAX_ATTEMPTS);
        when(aiJobMapper.selectOnePendingForUpdate()).thenReturn(job);

        AiJob claimed = service.claimNext();
        assertThat(claimed).isNull();

        ArgumentCaptor<AiJob> captor = ArgumentCaptor.forClass(AiJob.class);
        verify(aiJobMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AiJobStatus.FAILED);
        assertThat(captor.getValue().getErrorCode()).isEqualTo(AiJobErrorCodes.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("owner 可读自己的 job；跨教师 → 404")
    void ownerCanRead_crossTeacher404() {
        AiJob job = pendingJob(5L, 10L);
        when(aiJobMapper.selectById(5L)).thenReturn(job);

        AiJobVO vo = service.getForOwner(5L);
        assertThat(vo.getId()).isEqualTo(5L);

        CurrentUser.set(new CurrentPrincipal(99L, UserRole.TEACHER));
        assertThatThrownBy(() -> service.getForOwner(5L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("特性开关关闭 → 派发 503")
    void featureFlagOff_dispatch503() {
        featureFlags.setAiJobDispatchEnabled(false);
        assertThatThrownBy(() -> service.dispatch(1L, AiJobKind.QUESTION_GENERATE, "k", Map.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        verify(aiJobMapper, never()).insert(any(AiJob.class));
    }

    @Test
    @DisplayName("PENDING 取消 → CANCELLED；RUNNING 取消登记协作标志")
    void cancel_pendingImmediate_runningCooperative() {
        AiJob pending = pendingJob(1L, 10L);
        when(aiJobMapper.selectById(1L)).thenReturn(pending);

        AiJobVO cancelled = service.cancel(1L);
        assertThat(cancelled.getStatus()).isEqualTo(AiJobStatus.CANCELLED);

        AiJob running = pendingJob(2L, 10L);
        running.setStatus(AiJobStatus.RUNNING);
        when(aiJobMapper.selectById(2L)).thenReturn(running);

        service.cancel(2L);
        assertThat(service.isCancelRequested(2L)).isTrue();
        assertThat(running.getStatus()).isEqualTo(AiJobStatus.RUNNING);
    }

    private static AiJob pendingJob(Long id, Long ownerId) {
        AiJob j = new AiJob();
        j.setId(id);
        j.setOwnerId(ownerId);
        j.setCourseId(1L);
        j.setKind(AiJobKind.QUESTION_GENERATE);
        j.setRequestKey("rk-" + id);
        j.setStatus(AiJobStatus.PENDING);
        j.setProgress(0);
        j.setAttempts(0);
        j.setRequestJson("{\"nodeCode\":\"KT07\",\"count\":1}");
        j.setCreatedAt(LocalDateTime.now());
        return j;
    }
}
