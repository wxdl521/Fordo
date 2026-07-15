package com.wenjin.service;

import com.wenjin.dto.AiJobVO;
import com.wenjin.mapper.AiJobMapper;
import com.wenjin.support.AiJobPayloadLifecycle;
import com.wenjin.support.AiJobPayloadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Stores a local payload and commits its durable job reference under one lifecycle lock. */
@Service
public class AiJobPayloadSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(AiJobPayloadSubmissionService.class);

    private final AiJobService aiJobService;
    private final AiJobPayloadStore payloadStore;
    private final AiJobPayloadLifecycle payloadLifecycle;
    private final AiJobMapper aiJobMapper;

    public AiJobPayloadSubmissionService(AiJobService aiJobService,
                                         AiJobPayloadStore payloadStore,
                                         AiJobPayloadLifecycle payloadLifecycle,
                                         AiJobMapper aiJobMapper) {
        this.aiJobService = aiJobService;
        this.payloadStore = payloadStore;
        this.payloadLifecycle = payloadLifecycle;
        this.aiJobMapper = aiJobMapper;
    }

    public AiJobVO submitForCourse(Long courseId,
                                   String kind,
                                   String requestKey,
                                   MultipartFile file) {
        return submitPayload(file,
                metadata -> aiJobService.dispatch(courseId, kind, requestKey, metadata));
    }

    public AiJobVO submitForCourseCode(String courseCode,
                                       String kind,
                                       String requestKey,
                                       MultipartFile file) {
        return submitPayload(file, metadata -> {
            metadata.put("courseCode", courseCode);
            return aiJobService.dispatchByCourseCode(courseCode, kind, requestKey, metadata);
        });
    }

    private AiJobVO submitPayload(MultipartFile file,
                                  Function<Map<String, Object>, AiJobVO> dispatch) {
        String[] storedHash = new String[1];
        try {
            SubmissionResult result = payloadLifecycle.withSubmission(() -> {
                Map<String, Object> metadata = new HashMap<>(payloadStore.store(file));
                String fileHash = validHash(metadata.get("fileHash"));
                storedHash[0] = fileHash;
                AiJobVO job = dispatch.apply(metadata);
                return new SubmissionResult(job, returnedJobDoesNotReference(job, fileHash));
            });
            if (result.cleanupCandidate()) {
                cleanupIfStillUnreferenced(storedHash[0]);
            }
            return result.job();
        } catch (RuntimeException original) {
            cleanupIfStillUnreferenced(storedHash[0]);
            throw original;
        }
    }

    private boolean returnedJobDoesNotReference(AiJobVO job, String fileHash) {
        if (fileHash == null) {
            return false;
        }
        if (job == null || job.getId() == null) {
            return true;
        }
        try {
            return aiJobMapper.countJobPayloadReference(job.getId(), fileHash) == 0;
        } catch (RuntimeException e) {
            log.warn("Unable to verify AI job payload reference jobId={}, hash={}: {}",
                    job.getId(), fileHash, e.getMessage());
            return false;
        }
    }

    private void cleanupIfStillUnreferenced(String fileHash) {
        if (fileHash == null) {
            return;
        }
        try {
            payloadLifecycle.withCleanup(() -> {
                try {
                    if (aiJobMapper.countPayloadReferences(fileHash) == 0) {
                        payloadStore.deleteQuietly(fileHash);
                    }
                } catch (RuntimeException e) {
                    log.warn("Unable to clean orphan AI job payload hash={}: {}", fileHash, e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Unable to enter AI job payload cleanup scope hash={}: {}", fileHash, e.getMessage());
        }
    }

    private static String validHash(Object value) {
        if (value instanceof String hash && hash.matches("^[a-f0-9]{64}$")) {
            return hash;
        }
        return null;
    }

    private record SubmissionResult(AiJobVO job, boolean cleanupCandidate) {
    }
}
