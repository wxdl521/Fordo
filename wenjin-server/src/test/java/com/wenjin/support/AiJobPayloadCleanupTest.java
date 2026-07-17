package com.wenjin.support;

import com.wenjin.mapper.AiJobMapper;
import com.wenjin.service.AiJobPayloadSubmissionService;
import com.wenjin.service.AiJobService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiJobPayloadCleanupTest {

    @TempDir Path tempDir;
    @Mock private AiJobMapper aiJobMapper;
    @Mock private AiJobPayloadStore payloadStore;
    @Mock private AiJobService aiJobService;

    @Test
    void cleanup_deletesEachEligibleHashAndContinuesAfterOneFailure() {
        String first = "a".repeat(64);
        String failing = "b".repeat(64);
        String last = "c".repeat(64);
        when(aiJobMapper.selectPayloadHashesEligibleForDeletion(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(first, failing, last));
        when(payloadStore.deleteAndConfirmAbsent(anyString())).thenAnswer(invocation -> {
            if (failing.equals(invocation.getArgument(0))) {
                throw new IllegalStateException("disk unavailable");
            }
            return true;
        });
        AiJobPayloadCleanup cleanup = new AiJobPayloadCleanup(
                aiJobMapper, payloadStore, new AiJobPayloadLifecycle(), 24L, 100);
        LocalDateTime expectedCutoff = LocalDateTime.now().minusHours(24);

        cleanup.cleanup();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(aiJobMapper).selectPayloadHashesEligibleForDeletion(cutoff.capture(), eq(100));
        assertThat(cutoff.getValue()).isBetween(
                expectedCutoff.minusSeconds(1), expectedCutoff.plusSeconds(1));
        InOrder order = inOrder(payloadStore);
        order.verify(payloadStore).deleteAndConfirmAbsent(first);
        order.verify(payloadStore).deleteAndConfirmAbsent(failing);
        order.verify(payloadStore).deleteAndConfirmAbsent(last);
    }

    @Test
    void cleanupWaitsUntilUploadStoreAndDispatchScopeReturns() throws Exception {
        AiJobPayloadLifecycle lifecycle = new AiJobPayloadLifecycle();
        AiJobPayloadCleanup cleanup = new AiJobPayloadCleanup(
                aiJobMapper, payloadStore, lifecycle, 24L, 100);
        AiJobPayloadSubmissionService submission = new AiJobPayloadSubmissionService(
                aiJobService, payloadStore, lifecycle, aiJobMapper);
        MockMultipartFile file = new MockMultipartFile(
                "file", "questions.xlsx", "application/octet-stream", new byte[]{1});
        when(payloadStore.store(file)).thenReturn(java.util.Map.of(
                "fileHash", "a".repeat(64),
                "fileSize", 1,
                "fileName", "questions.xlsx",
                "contentType", "application/octet-stream"));
        when(aiJobMapper.countPayloadReferences("a".repeat(64))).thenReturn(1);
        CountDownLatch dispatchEntered = new CountDownLatch(1);
        CountDownLatch allowDispatchCommit = new CountDownLatch(1);
        CountDownLatch cleanupQueryEntered = new CountDownLatch(1);
        when(aiJobService.dispatch(
                7L, AiJobKind.QUESTION_IMPORT_EXCEL, "request-key", java.util.Map.of(
                        "fileHash", "a".repeat(64),
                        "fileSize", 1,
                        "fileName", "questions.xlsx",
                        "contentType", "application/octet-stream")))
                .thenAnswer(invocation -> {
                    dispatchEntered.countDown();
                    await(allowDispatchCommit);
                    return new com.wenjin.dto.AiJobVO();
                });
        when(aiJobMapper.selectPayloadHashesEligibleForDeletion(any(LocalDateTime.class), eq(100)))
                .thenAnswer(invocation -> {
                    cleanupQueryEntered.countDown();
                    return List.of();
                });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> upload = pool.submit(() -> submission.submitForCourse(
                    7L, AiJobKind.QUESTION_IMPORT_EXCEL, "request-key", file));
            assertThat(dispatchEntered.await(2, TimeUnit.SECONDS)).isTrue();

            CountDownLatch cleanupContenderStarted = new CountDownLatch(1);
            Future<?> cleanupRun = pool.submit(() -> {
                cleanupContenderStarted.countDown();
                cleanup.cleanup();
            });

            assertThat(cleanupContenderStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cleanupQueryEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
            allowDispatchCommit.countDown();
            upload.get(2, TimeUnit.SECONDS);
            cleanupRun.get(2, TimeUnit.SECONDS);
            assertThat(cleanupQueryEntered.getCount()).isZero();
            verify(payloadStore, never()).deleteQuietly(anyString());
        } finally {
            allowDispatchCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void submissionWaitsUntilCleanupFinishesDeletingEntireBatch() throws Exception {
        String hash = "e".repeat(64);
        AiJobPayloadLifecycle lifecycle = new AiJobPayloadLifecycle();
        AiJobPayloadCleanup cleanup = new AiJobPayloadCleanup(
                aiJobMapper, payloadStore, lifecycle, 24L, 100);
        AiJobPayloadSubmissionService submission = new AiJobPayloadSubmissionService(
                aiJobService, payloadStore, lifecycle, aiJobMapper);
        MockMultipartFile file = new MockMultipartFile(
                "file", "questions.xlsx", "application/octet-stream", new byte[]{1});
        CountDownLatch deleteEntered = new CountDownLatch(1);
        CountDownLatch allowDelete = new CountDownLatch(1);
        CountDownLatch storeEntered = new CountDownLatch(1);
        when(aiJobMapper.selectPayloadHashesEligibleForDeletion(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(hash));
        when(aiJobMapper.countPayloadReferences(hash)).thenReturn(1);
        doAnswer(invocation -> {
            deleteEntered.countDown();
            await(allowDelete);
            return true;
        }).when(payloadStore).deleteAndConfirmAbsent(hash);
        when(payloadStore.store(file)).thenAnswer(invocation -> {
            storeEntered.countDown();
            return java.util.Map.of(
                    "fileHash", hash,
                    "fileSize", 1,
                    "fileName", "questions.xlsx",
                    "contentType", "application/octet-stream");
        });
        when(aiJobService.dispatch(
                7L, AiJobKind.QUESTION_IMPORT_EXCEL, "new-request", java.util.Map.of(
                        "fileHash", hash,
                        "fileSize", 1,
                        "fileName", "questions.xlsx",
                        "contentType", "application/octet-stream")))
                .thenReturn(new com.wenjin.dto.AiJobVO());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cleanupRun = pool.submit(cleanup::cleanup);
            assertThat(deleteEntered.await(2, TimeUnit.SECONDS)).isTrue();

            CountDownLatch submissionContenderStarted = new CountDownLatch(1);
            Future<?> upload = pool.submit(() -> {
                submissionContenderStarted.countDown();
                submission.submitForCourse(
                        7L, AiJobKind.QUESTION_IMPORT_EXCEL, "new-request", file);
            });

            assertThat(submissionContenderStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(storeEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
            allowDelete.countDown();
            cleanupRun.get(2, TimeUnit.SECONDS);
            upload.get(2, TimeUnit.SECONDS);
            assertThat(storeEntered.getCount()).isZero();
        } finally {
            allowDelete.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void cleanupRecoversOldUnreferencedFinalAndTempFilesAfterProcessCrash() throws Exception {
        AiJobPayloadStore realStore = new AiJobPayloadStore(tempDir.toString());
        byte[] orphanContent = new byte[]{1, 2, 3};
        byte[] recentContent = new byte[]{4, 5, 6};
        byte[] referencedContent = new byte[]{7, 8, 9};
        String orphanHash = (String) realStore.storeBytes(
                orphanContent, "orphan.bin", "application/octet-stream").get("fileHash");
        String recentHash = (String) realStore.storeBytes(
                recentContent, "recent.bin", "application/octet-stream").get("fileHash");
        String referencedHash = (String) realStore.storeBytes(
                referencedContent, "referenced.bin", "application/octet-stream").get("fileHash");
        Path orphan = tempDir.resolve(orphanHash);
        Path recent = tempDir.resolve(recentHash);
        Path referenced = tempDir.resolve(referencedHash);
        Path staleTemp = tempDir.resolve(orphanHash + ".crashed.tmp");
        Files.write(staleTemp, new byte[]{9});
        FileTime stale = FileTime.from(Instant.now().minusSeconds(48 * 60 * 60));
        Files.setLastModifiedTime(orphan, stale);
        Files.setLastModifiedTime(referenced, stale);
        Files.setLastModifiedTime(staleTemp, stale);
        AiJobPayloadCleanup cleanup = new AiJobPayloadCleanup(
                aiJobMapper, realStore, new AiJobPayloadLifecycle(), 24L, 100);
        when(aiJobMapper.selectAllReferencedPayloadHashes()).thenReturn(List.of(referencedHash));
        when(aiJobMapper.selectPayloadHashesEligibleForDeletion(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of());

        cleanup.cleanup();

        assertThat(Files.exists(orphan)).isFalse();
        assertThat(Files.exists(staleTemp)).isFalse();
        assertThat(Files.exists(recent)).isTrue();
        assertThat(Files.exists(referenced)).isTrue();
    }

    @Test
    void cleanupLimitsEachFilesystemDeletionBatch() throws Exception {
        AiJobPayloadStore realStore = new AiJobPayloadStore(tempDir.toString());
        FileTime stale = FileTime.from(Instant.now().minusSeconds(48 * 60 * 60));
        for (byte value = 1; value <= 3; value++) {
            String hash = (String) realStore.storeBytes(
                    new byte[]{value}, "orphan.bin", "application/octet-stream").get("fileHash");
            Files.setLastModifiedTime(tempDir.resolve(hash), stale);
        }
        when(aiJobMapper.selectAllReferencedPayloadHashes()).thenReturn(List.of());
        when(aiJobMapper.selectPayloadHashesEligibleForDeletion(any(LocalDateTime.class), eq(2)))
                .thenReturn(List.of());
        AiJobPayloadCleanup cleanup = new AiJobPayloadCleanup(
                aiJobMapper, realStore, new AiJobPayloadLifecycle(), 24L, 2);

        cleanup.cleanup();

        try (var entries = Files.list(tempDir)) {
            assertThat(entries.filter(Files::isRegularFile)).hasSize(1);
        }
        verify(aiJobMapper).selectPayloadHashesEligibleForDeletion(any(LocalDateTime.class), eq(2));
    }

    @Test
    void cleanupClearsOldTerminalReferencesOnlyAfterDeletionIsConfirmed() {
        String deleted = "1".repeat(64);
        String failed = "2".repeat(64);
        when(aiJobMapper.selectAllReferencedPayloadHashes()).thenReturn(List.of(deleted, failed));
        when(aiJobMapper.selectPayloadHashesEligibleForDeletion(any(LocalDateTime.class), eq(2)))
                .thenReturn(List.of(deleted, failed));
        when(payloadStore.deleteAndConfirmAbsent(deleted)).thenReturn(true);
        when(payloadStore.deleteAndConfirmAbsent(failed)).thenReturn(false);
        AiJobPayloadCleanup cleanup = new AiJobPayloadCleanup(
                aiJobMapper, payloadStore, new AiJobPayloadLifecycle(), 24L, 2);

        cleanup.cleanup();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(aiJobMapper).clearPayloadReferencesAfterDeletion(eq(deleted), cutoff.capture());
        verify(aiJobMapper, never()).clearPayloadReferencesAfterDeletion(eq(failed), any());
        verify(payloadStore, times(1)).deleteAndConfirmAbsent(deleted);
        verify(payloadStore, times(1)).deleteAndConfirmAbsent(failed);
        assertThat(cutoff.getValue()).isBefore(LocalDateTime.now());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
