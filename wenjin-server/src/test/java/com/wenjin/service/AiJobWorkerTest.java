package com.wenjin.service;

import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.GenerateResult;
import com.wenjin.entity.AiJob;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.PrincipalLoader;
import com.wenjin.security.UserRole;
import com.wenjin.service.memory.MemoryDistillService;
import com.wenjin.service.memory.SummaryRefreshService;
import com.wenjin.service.multiagent.SupervisorSampleReportService;
import com.wenjin.service.multiagent.TeacherWeeklyReportService;
import com.wenjin.support.AiJobErrorCodes;
import com.wenjin.support.AiJobHeartbeatScheduler;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.AiJobPayloadStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiJobWorkerTest {

    @Mock private AiJobService aiJobService;
    @Mock private AiJobPayloadStore payloadStore;
    @Mock private QuestionService questionService;
    @Mock private QuestionBankImportService questionBankImportService;
    @Mock private GraphExtractReviewService graphExtractReviewService;
    @Mock private GraphImportService graphImportService;
    @Mock private MemoryDistillService memoryDistillService;
    @Mock private SummaryRefreshService summaryRefreshService;
    @Mock private com.wenjin.service.rag.DocIngestService docIngestService;
    @Mock private TeacherWeeklyReportService teacherWeeklyReportService;
    @Mock private SupervisorSampleReportService supervisorSampleReportService;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private AiJobHeartbeatScheduler heartbeatScheduler;
    @Mock private ScheduledFuture<?> heartbeatFuture;

    private FeatureFlagsProperties featureFlags;
    private AiJobWorker worker;

    @BeforeEach
    void setUp() {
        featureFlags = new FeatureFlagsProperties();
        featureFlags.setAiJobDispatchEnabled(true);
        lenient().when(sysUserMapper.selectById(10L)).thenReturn(user(1, 1));
        doReturn(heartbeatFuture).when(heartbeatScheduler).scheduleAtFixedRate(
                any(Runnable.class), anyLong());
        worker = new AiJobWorker(
                aiJobService,
                payloadStore,
                questionService,
                questionBankImportService,
                graphExtractReviewService,
                graphImportService,
                memoryDistillService,
                summaryRefreshService,
                docIngestService,
                teacherWeeklyReportService,
                supervisorSampleReportService,
                new PrincipalLoader(sysUserMapper),
                featureFlags,
                heartbeatScheduler,
                1_000L);
    }

    @AfterEach
    void clearPrincipal() {
        CurrentUser.clear();
    }

    @Test
    void disabledKillSwitch_returnsBeforeRecoveryAndClaim() {
        featureFlags.setAiJobDispatchEnabled(false);

        worker.tick();

        verify(aiJobService, never()).recoverStale();
        verify(aiJobService, never()).claimNext();
    }

    @Test
    void missingActor_isLeaseQualifiedRevokedWithoutEffectOrDomainWork() {
        when(sysUserMapper.selectById(10L)).thenReturn(null);

        worker.execute(claimedJob("claimed-lease"));

        assertActorRevokedWithoutEffects();
    }

    @Test
    void disabledActor_isLeaseQualifiedRevokedWithoutEffectOrDomainWork() {
        when(sysUserMapper.selectById(10L)).thenReturn(user(0, 1));

        worker.execute(claimedJob("claimed-lease"));

        assertActorRevokedWithoutEffects();
    }

    @Test
    void unknownActorRole_isLeaseQualifiedRevokedWithoutEffectOrDomainWork() {
        when(sysUserMapper.selectById(10L)).thenReturn(user(1, 99));

        worker.execute(claimedJob("claimed-lease"));

        assertActorRevokedWithoutEffects();
    }

    @Test
    void studentActor_isLeaseQualifiedRevokedWithoutEffectOrDomainWork() {
        when(sysUserMapper.selectById(10L)).thenReturn(user(1, 2));

        worker.execute(claimedJob("claimed-lease"));

        assertActorRevokedWithoutEffects();
    }

    @Test
    void adminActor_remainsAdminDuringDomainExecution() {
        AiJob job = claimedJob("claimed-lease");
        when(sysUserMapper.selectById(10L)).thenReturn(user(1, 3));
        allowCurrentLease(job);
        when(aiJobService.markProgress(eq(job.getId()), eq("claimed-lease"), anyInt()))
                .thenReturn(true);
        when(aiJobService.markEffectStarted(job.getId(), "claimed-lease")).thenReturn(true);
        when(aiJobService.parseRequestMap(job.getRequestJson()))
                .thenReturn(Map.of("nodeCode", "KT01", "count", 1));
        when(questionService.generate(job.getCourseId(), "KT01", 1)).thenAnswer(invocation -> {
            assertThat(CurrentUser.getPrincipal())
                    .isEqualTo(new CurrentPrincipal(10L, UserRole.ADMIN));
            return new GenerateResult();
        });
        when(aiJobService.markSucceeded(eq(job.getId()), eq("claimed-lease"), any()))
                .thenReturn(true);

        worker.execute(job);

        verify(questionService).generate(job.getCourseId(), "KT01", 1);
        assertThat(CurrentUser.getPrincipal()).isNull();
    }

    @Test
    void heartbeatLoss_usesCapturedLeaseAndSuppressesLaterProgressAndTerminalWrites() {
        AiJob job = claimedJob("claimed-lease");
        allowCurrentLease(job);
        when(aiJobService.markProgress(job.getId(), "claimed-lease", 5)).thenReturn(true);
        when(aiJobService.markProgress(job.getId(), "claimed-lease", 20)).thenReturn(true);
        when(aiJobService.markEffectStarted(job.getId(), "claimed-lease")).thenReturn(true);
        when(aiJobService.parseRequestMap(job.getRequestJson()))
                .thenReturn(Map.of("nodeCode", "KT01", "count", 1));
        when(aiJobService.heartbeat(job.getId(), "claimed-lease")).thenReturn(false);
        AtomicReference<Runnable> heartbeat = new AtomicReference<>();
        doAnswer(invocation -> {
            heartbeat.set(invocation.getArgument(0));
            return heartbeatFuture;
        }).when(heartbeatScheduler).scheduleAtFixedRate(any(Runnable.class), anyLong());
        when(questionService.generate(job.getCourseId(), "KT01", 1)).thenAnswer(invocation -> {
            job.setLeaseToken("replacement-lease");
            heartbeat.get().run();
            return new GenerateResult();
        });

        worker.execute(job);

        verify(aiJobService).heartbeat(job.getId(), "claimed-lease");
        verify(aiJobService, never()).heartbeat(job.getId(), "replacement-lease");
        verify(aiJobService, never()).markProgress(job.getId(), "claimed-lease", 90);
        verify(aiJobService, never()).markSucceeded(any(), anyString(), any());
        verify(aiJobService, never()).markFailed(any(), anyString(), anyString());
        verify(heartbeatFuture, times(1)).cancel(false);
    }

    @Test
    void heartbeatException_keepsScheduleAndAllowsLaterSuccessfulPulse() {
        AiJob job = claimedJob("claimed-lease");
        allowCurrentLease(job);
        when(aiJobService.markProgress(eq(job.getId()), eq("claimed-lease"), anyInt()))
                .thenReturn(true);
        when(aiJobService.markEffectStarted(job.getId(), "claimed-lease")).thenReturn(true);
        when(aiJobService.parseRequestMap(job.getRequestJson()))
                .thenReturn(Map.of("nodeCode", "KT01", "count", 1));
        when(aiJobService.heartbeat(job.getId(), "claimed-lease"))
                .thenThrow(new IllegalStateException("temporary database outage"))
                .thenReturn(true);
        when(aiJobService.markSucceeded(eq(job.getId()), eq("claimed-lease"), any()))
                .thenReturn(true);
        AtomicReference<Runnable> heartbeat = new AtomicReference<>();
        doAnswer(invocation -> {
            heartbeat.set(invocation.getArgument(0));
            return heartbeatFuture;
        }).when(heartbeatScheduler).scheduleAtFixedRate(any(Runnable.class), anyLong());
        when(questionService.generate(job.getCourseId(), "KT01", 1)).thenAnswer(invocation -> {
            heartbeat.get().run();
            verify(heartbeatFuture, never()).cancel(false);
            heartbeat.get().run();
            return new GenerateResult();
        });

        worker.execute(job);

        verify(aiJobService, times(2)).heartbeat(job.getId(), "claimed-lease");
        verify(aiJobService).markProgress(job.getId(), "claimed-lease", 90);
        verify(aiJobService).markSucceeded(eq(job.getId()), eq("claimed-lease"), any());
        verify(heartbeatFuture).cancel(false);
    }

    @Test
    void longDomainWork_heartbeatsConcurrentlyOnDedicatedScheduler() throws Exception {
        AiJob job = claimedJob("claimed-lease");
        allowCurrentLease(job);
        when(aiJobService.markProgress(eq(job.getId()), eq("claimed-lease"), anyInt()))
                .thenReturn(true);
        when(aiJobService.markEffectStarted(job.getId(), "claimed-lease")).thenReturn(true);
        when(aiJobService.parseRequestMap(job.getRequestJson()))
                .thenReturn(Map.of("nodeCode", "KT01", "count", 1));
        CountDownLatch domainStarted = new CountDownLatch(1);
        CountDownLatch heartbeatObserved = new CountDownLatch(1);
        when(aiJobService.heartbeat(job.getId(), "claimed-lease")).thenAnswer(invocation -> {
            heartbeatObserved.countDown();
            return true;
        });
        when(questionService.generate(job.getCourseId(), "KT01", 1)).thenAnswer(invocation -> {
            domainStarted.countDown();
            assertThat(heartbeatObserved.await(2, TimeUnit.SECONDS)).isTrue();
            return new GenerateResult();
        });

        AiJobHeartbeatScheduler dedicatedScheduler = new AiJobHeartbeatScheduler(1);
        ExecutorService tickThread = Executors.newSingleThreadExecutor();
        try {
            AiJobWorker dedicatedWorker = new AiJobWorker(
                    aiJobService,
                    payloadStore,
                    questionService,
                    questionBankImportService,
                    graphExtractReviewService,
                    graphImportService,
                    memoryDistillService,
                    summaryRefreshService,
                    docIngestService,
                    teacherWeeklyReportService,
                    supervisorSampleReportService,
                    new PrincipalLoader(sysUserMapper),
                    featureFlags,
                    dedicatedScheduler,
                    20L);

            Future<?> execution = tickThread.submit(() -> dedicatedWorker.execute(job));

            assertThat(domainStarted.await(2, TimeUnit.SECONDS)).isTrue();
            execution.get(3, TimeUnit.SECONDS);
            verify(aiJobService, atLeastOnce()).heartbeat(job.getId(), "claimed-lease");
        } finally {
            tickThread.shutdownNow();
            dedicatedScheduler.close();
        }
    }

    @Test
    void failedInitialProgressCas_stopsBeforeEffectAndDomainWork() {
        AiJob job = claimedJob("claimed-lease");
        allowCurrentLease(job);
        when(aiJobService.markProgress(job.getId(), "claimed-lease", 5)).thenReturn(false);

        worker.execute(job);

        verify(aiJobService, never()).markEffectStarted(any(), anyString());
        verify(aiJobService, never()).parseRequestMap(anyString());
        verify(questionService, never()).generate(any(), any(), anyInt());
        verify(aiJobService, never()).markSucceeded(any(), anyString(), any());
        verify(aiJobService, never()).markFailed(any(), anyString(), anyString());
    }

    @Test
    void failedEffectBoundaryCas_stopsBeforeDomainAndTerminalWork() {
        AiJob job = claimedJob("claimed-lease");
        allowCurrentLease(job);
        when(aiJobService.markProgress(job.getId(), "claimed-lease", 5)).thenReturn(true);
        when(aiJobService.markEffectStarted(job.getId(), "claimed-lease")).thenReturn(false);

        worker.execute(job);

        verify(aiJobService, never()).parseRequestMap(anyString());
        verify(questionService, never()).generate(any(), any(), anyInt());
        verify(aiJobService, never()).markSucceeded(any(), anyString(), any());
        verify(aiJobService, never()).markFailed(any(), anyString(), anyString());
    }

    @Test
    void postCommandProgressLeaseLoss_keepsCapturedTokenAndSuppressesTerminalWrite() {
        AiJob job = claimedJob("claimed-lease");
        allowCurrentLease(job);
        when(aiJobService.markEffectStarted(job.getId(), "claimed-lease")).thenReturn(true);
        when(aiJobService.parseRequestMap(job.getRequestJson()))
                .thenReturn(Map.of("nodeCode", "KT01", "count", 1));
        when(aiJobService.markProgress(eq(job.getId()), eq("claimed-lease"), anyInt()))
                .thenAnswer(invocation -> invocation.<Integer>getArgument(2) != 90);
        when(questionService.generate(job.getCourseId(), "KT01", 1)).thenAnswer(invocation -> {
            job.setLeaseToken("replacement-lease");
            GenerateResult result = new GenerateResult();
            result.setGenerated(1);
            return result;
        });

        worker.execute(job);

        verify(questionService).generate(job.getCourseId(), "KT01", 1);
        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(aiJobService, org.mockito.Mockito.times(3))
                .markProgress(eq(job.getId()), tokens.capture(), anyInt());
        assertThat(tokens.getAllValues()).containsOnly("claimed-lease");
        verify(aiJobService, never()).markSucceeded(any(), anyString(), any());
        verify(aiJobService, never()).markFailed(any(), anyString(), anyString());
        verify(aiJobService, never()).markProgress(any(), eq("replacement-lease"), anyInt());
    }

    private void allowCurrentLease(AiJob job) {
        when(aiJobService.isCancelRequested(job.getId(), "claimed-lease")).thenReturn(false);
        when(aiJobService.isCurrentLease(job.getId(), "claimed-lease")).thenReturn(true);
    }

    private void assertActorRevokedWithoutEffects() {
        verify(aiJobService).markFailed(41L, "claimed-lease", AiJobErrorCodes.JOB_ACTOR_REVOKED);
        verify(aiJobService, never()).markEffectStarted(any(), anyString());
        verify(aiJobService, never()).markProgress(any(), anyString(), anyInt());
        verify(aiJobService, never()).parseRequestMap(anyString());
        verify(questionService, never()).generate(any(), any(), anyInt());
        verify(heartbeatScheduler, never()).scheduleAtFixedRate(any(Runnable.class), anyLong());
    }

    private static SysUser user(Integer status, Integer role) {
        SysUser user = new SysUser();
        user.setId(10L);
        user.setStatus(status);
        user.setRole(role);
        return user;
    }

    private AiJob docIngestJob() {
        AiJob job = claimedJob("claimed-lease");
        job.setKind(AiJobKind.DOC_INGEST);
        job.setRequestJson("{\"sourceId\":5,\"fileHash\":\"h\",\"fileName\":\"a.pdf\"}");
        allowCurrentLease(job);
        when(aiJobService.markProgress(eq(job.getId()), eq("claimed-lease"), anyInt())).thenReturn(true);
        when(aiJobService.markEffectStarted(job.getId(), "claimed-lease")).thenReturn(true);
        when(aiJobService.parseRequestMap(job.getRequestJson()))
                .thenReturn(Map.of("sourceId", 5, "fileHash", "h", "fileName", "a.pdf"));
        return job;
    }

    @Test
    void docIngest_dispatchesBySourceWithFileName() {
        AiJob job = docIngestJob();
        byte[] bytes = {1, 2};
        when(payloadStore.load("h")).thenReturn(bytes);
        when(docIngestService.ingestSource(5L, bytes, "a.pdf")).thenReturn(Map.of("status", "READY"));
        when(aiJobService.markSucceeded(eq(job.getId()), eq("claimed-lease"), any())).thenReturn(true);

        worker.execute(job);

        verify(docIngestService).ingestSource(5L, bytes, "a.pdf");
        verify(docIngestService, never()).markFailed(5L);
        verify(aiJobService).markSucceeded(eq(job.getId()), eq("claimed-lease"), any());
    }

    @Test
    void docIngest_exceptionMarksSourceFailedAndJobFailed() {
        AiJob job = docIngestJob();
        when(payloadStore.load("h")).thenReturn(new byte[]{1});
        when(docIngestService.ingestSource(eq(5L), any(), eq("a.pdf")))
                .thenThrow(new IllegalStateException("boom"));

        worker.execute(job);

        verify(docIngestService).markFailed(5L);
        verify(aiJobService).markFailed(job.getId(), "claimed-lease", AiJobErrorCodes.INTERNAL_ERROR);
    }

    private static AiJob claimedJob(String leaseToken) {
        AiJob job = new AiJob();
        job.setId(41L);
        job.setOwnerId(10L);
        job.setCourseId(7L);
        job.setKind(AiJobKind.QUESTION_GENERATE);
        job.setRequestJson("{\"nodeCode\":\"KT01\",\"count\":1}");
        job.setLeaseToken(leaseToken);
        return job;
    }
}
