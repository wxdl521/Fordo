package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AnnotateRequest;
import com.wenjin.entity.AiJob;
import com.wenjin.support.AiJobErrorCodes;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.AiJobPayloadStore;
import com.wenjin.support.InMemoryMultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Polls, executes, and lease-qualifies every write made by an AI job worker. */
@Component
public class AiJobWorker {

    private static final Logger log = LoggerFactory.getLogger(AiJobWorker.class);

    private final AiJobService aiJobService;
    private final AiJobPayloadStore payloadStore;
    private final QuestionService questionService;
    private final QuestionBankImportService questionBankImportService;
    private final GraphExtractReviewService graphExtractReviewService;
    private final GraphImportService graphImportService;

    public AiJobWorker(AiJobService aiJobService,
                       AiJobPayloadStore payloadStore,
                       QuestionService questionService,
                       QuestionBankImportService questionBankImportService,
                       GraphExtractReviewService graphExtractReviewService,
                       GraphImportService graphImportService) {
        this.aiJobService = aiJobService;
        this.payloadStore = payloadStore;
        this.questionService = questionService;
        this.questionBankImportService = questionBankImportService;
        this.graphExtractReviewService = graphExtractReviewService;
        this.graphImportService = graphImportService;
    }

    @Scheduled(fixedDelayString = "${wenjin.jobs.poll-interval-ms:2000}")
    public void tick() {
        try {
            aiJobService.recoverStale();
        } catch (Exception e) {
            log.warn("AI job recoverStale failed: {}", e.getMessage());
        }
        AiJob job;
        try {
            job = aiJobService.claimNext();
        } catch (Exception e) {
            log.warn("AI job claimNext failed: {}", e.getMessage());
            return;
        }
        if (job != null) {
            execute(job);
        }
    }

    void execute(AiJob job) {
        Long jobId = job.getId();
        // Capture once: a stale worker must never adopt a replacement attempt's token.
        String leaseToken = job.getLeaseToken();
        if (checkCancelOrLeaseLoss(jobId, leaseToken)) {
            return;
        }
        try (AiJobService.WorkerPrincipalScope ignored =
                     new AiJobService.WorkerPrincipalScope(job.getOwnerId())) {
            markProgressOrLose(jobId, leaseToken, 5);
            if (checkCancelOrLeaseLoss(jobId, leaseToken)) {
                return;
            }
            if (!aiJobService.markEffectStarted(jobId, leaseToken)) {
                return;
            }
            Object result = runKind(job, leaseToken);
            if (checkCancelOrLeaseLoss(jobId, leaseToken)) {
                return;
            }
            aiJobService.markSucceeded(jobId, leaseToken, result);
        } catch (LeaseLostException e) {
            log.info("AI job {} stopped after lease loss", jobId);
        } catch (BusinessException e) {
            String code = mapBusinessError(e);
            log.warn("AI job {} failed: code={} msg={}", jobId, code, e.getMessage());
            aiJobService.markFailed(jobId, leaseToken, code);
        } catch (Exception e) {
            log.error("AI job {} internal error: {}", jobId, e.getMessage());
            aiJobService.markFailed(jobId, leaseToken, AiJobErrorCodes.INTERNAL_ERROR);
        }
    }

    private Object runKind(AiJob job, String leaseToken) {
        String kind = job.getKind();
        Map<String, Object> request = aiJobService.parseRequestMap(job.getRequestJson());
        return switch (kind) {
            case AiJobKind.QUESTION_GENERATE -> runGenerate(job, leaseToken, request);
            case AiJobKind.QUESTION_ANNOTATE -> runAnnotate(job, leaseToken);
            case AiJobKind.QUESTION_IMPORT_EXCEL -> runQuestionImportExcel(job, leaseToken, request);
            case AiJobKind.GRAPH_EXTRACT -> runGraphExtract(job, leaseToken, request);
            case AiJobKind.GRAPH_IMPORT_EXCEL -> runGraphImportExcel(job, leaseToken, request);
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "未知作业类型: " + kind);
        };
    }

    private Object runGenerate(AiJob job, String leaseToken, Map<String, Object> request) {
        String nodeCode = stringVal(request.get("nodeCode"));
        int count = intVal(request.get("count"), 5);
        markProgressOrLose(job.getId(), leaseToken, 20);
        Object result = questionService.generate(job.getCourseId(), nodeCode, count);
        markProgressOrLose(job.getId(), leaseToken, 90);
        return result;
    }

    private Object runAnnotate(AiJob job, String leaseToken) {
        AnnotateRequest body = aiJobService.parseRequest(job.getRequestJson(), AnnotateRequest.class);
        markProgressOrLose(job.getId(), leaseToken, 20);
        Object result = questionService.annotate(job.getCourseId(), body);
        markProgressOrLose(job.getId(), leaseToken, 90);
        return result;
    }

    private Object runQuestionImportExcel(AiJob job, String leaseToken, Map<String, Object> request) {
        InMemoryMultipartFile file = loadFile(request);
        markProgressOrLose(job.getId(), leaseToken, 15);
        Object result = questionBankImportService.importFromExcel(job.getCourseId(), file);
        markProgressOrLose(job.getId(), leaseToken, 90);
        return result;
    }

    private Object runGraphExtract(AiJob job, String leaseToken, Map<String, Object> request) {
        String courseCode = stringVal(request.get("courseCode"));
        InMemoryMultipartFile file = loadFile(request);
        markProgressOrLose(job.getId(), leaseToken, 10);
        Object result = graphExtractReviewService.extractAndStash(courseCode, file);
        markProgressOrLose(job.getId(), leaseToken, 90);
        return result;
    }

    private Object runGraphImportExcel(AiJob job, String leaseToken, Map<String, Object> request) {
        String courseCode = stringVal(request.get("courseCode"));
        InMemoryMultipartFile file = loadFile(request);
        markProgressOrLose(job.getId(), leaseToken, 15);
        Object result = graphImportService.importFromExcel(courseCode, file);
        markProgressOrLose(job.getId(), leaseToken, 90);
        return result;
    }

    private InMemoryMultipartFile loadFile(Map<String, Object> request) {
        String hash = stringVal(request.get("fileHash"));
        String fileName = stringVal(request.get("fileName"));
        String contentType = stringVal(request.get("contentType"));
        byte[] bytes = payloadStore.load(hash);
        return new InMemoryMultipartFile("file", fileName, contentType, bytes);
    }

    private boolean checkCancelOrLeaseLoss(Long jobId, String leaseToken) {
        if (aiJobService.isCancelRequested(jobId, leaseToken)) {
            aiJobService.markCancelled(jobId, leaseToken);
            return true;
        }
        return !aiJobService.isCurrentLease(jobId, leaseToken);
    }

    private void markProgressOrLose(Long jobId, String leaseToken, int progress) {
        if (!aiJobService.markProgress(jobId, leaseToken, progress)) {
            throw new LeaseLostException();
        }
    }

    private static String mapBusinessError(BusinessException e) {
        int code = e.getCode();
        if (code == ResultCode.AI_ERROR.getCode()) {
            return AiJobErrorCodes.AI_ERROR;
        }
        if (code == ResultCode.BAD_REQUEST.getCode()
                || code == ResultCode.GRAPH_VALIDATE_FAIL.getCode()) {
            return AiJobErrorCodes.BAD_REQUEST;
        }
        if (code == ResultCode.SERVICE_UNAVAILABLE.getCode()) {
            return AiJobErrorCodes.SERVICE_UNAVAILABLE;
        }
        if (code == ResultCode.NOT_FOUND.getCode()) {
            return AiJobErrorCodes.BAD_REQUEST;
        }
        return AiJobErrorCodes.INTERNAL_ERROR;
    }

    private static String stringVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intVal(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static final class LeaseLostException extends RuntimeException {
        private LeaseLostException() {
            super(null, null, false, false);
        }
    }
}
