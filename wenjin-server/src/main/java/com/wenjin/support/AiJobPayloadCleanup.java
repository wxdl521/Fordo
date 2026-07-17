package com.wenjin.support;

import com.wenjin.mapper.AiJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deletes payloads only after every database reference is old and terminal. */
@Component
public class AiJobPayloadCleanup {

    private static final Logger log = LoggerFactory.getLogger(AiJobPayloadCleanup.class);

    private final AiJobMapper aiJobMapper;
    private final AiJobPayloadStore payloadStore;
    private final AiJobPayloadLifecycle payloadLifecycle;
    private final long retentionHours;
    private final int batchSize;

    public AiJobPayloadCleanup(AiJobMapper aiJobMapper,
                               AiJobPayloadStore payloadStore,
                               AiJobPayloadLifecycle payloadLifecycle,
                               @Value("${wenjin.jobs.payload-retention-hours:168}") long retentionHours,
                               @Value("${wenjin.jobs.payload-cleanup-batch-size:100}") int batchSize) {
        this.aiJobMapper = aiJobMapper;
        this.payloadStore = payloadStore;
        this.payloadLifecycle = payloadLifecycle;
        this.retentionHours = Math.max(1L, retentionHours);
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${wenjin.jobs.payload-cleanup-interval-ms:3600000}")
    public void cleanup() {
        payloadLifecycle.withCleanup(this::cleanupLocked);
    }

    private void cleanupLocked() {
        Instant cutoff = Instant.now().minus(retentionHours, ChronoUnit.HOURS);
        LocalDateTime before = LocalDateTime.ofInstant(cutoff, ZoneId.systemDefault());
        List<String> referencedHashes;
        List<String> fileHashes;
        try {
            referencedHashes = aiJobMapper.selectAllReferencedPayloadHashes();
            fileHashes = aiJobMapper.selectPayloadHashesEligibleForDeletion(before, batchSize);
        } catch (RuntimeException e) {
            log.warn("AI job payload cleanup reference scan failed: {}", e.getMessage());
            return;
        }
        if (fileHashes != null) {
            for (String fileHash : fileHashes) {
                try {
                    if (payloadStore.deleteAndConfirmAbsent(fileHash)) {
                        aiJobMapper.clearPayloadReferencesAfterDeletion(fileHash, before);
                    } else {
                        log.warn("AI job payload deletion could not be confirmed for hash={}", fileHash);
                    }
                } catch (RuntimeException e) {
                    log.warn("AI job payload cleanup failed for hash={}: {}", fileHash, e.getMessage());
                }
            }
        }
        Set<String> referenced = referencedHashes == null
                ? new HashSet<>()
                : new HashSet<>(referencedHashes);
        referenced.remove(null);
        try {
            payloadStore.deleteOldUnreferencedFiles(referenced, cutoff, batchSize);
        } catch (RuntimeException e) {
            log.warn("AI job orphan payload scan failed: {}", e.getMessage());
        }
    }
}
