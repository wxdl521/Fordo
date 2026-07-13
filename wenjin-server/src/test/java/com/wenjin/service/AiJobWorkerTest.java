package com.wenjin.service;

import com.wenjin.config.CurrentUser;
import com.wenjin.dto.GenerateResult;
import com.wenjin.entity.AiJob;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.AiJobPayloadStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiJobWorkerTest {

    @Mock private AiJobService aiJobService;
    @Mock private AiJobPayloadStore payloadStore;
    @Mock private QuestionService questionService;
    @Mock private QuestionBankImportService questionBankImportService;
    @Mock private GraphExtractReviewService graphExtractReviewService;
    @Mock private GraphImportService graphImportService;

    private AiJobWorker worker;

    @BeforeEach
    void setUp() {
        worker = new AiJobWorker(
                aiJobService,
                payloadStore,
                questionService,
                questionBankImportService,
                graphExtractReviewService,
                graphImportService);
    }

    @AfterEach
    void clearPrincipal() {
        CurrentUser.clear();
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
