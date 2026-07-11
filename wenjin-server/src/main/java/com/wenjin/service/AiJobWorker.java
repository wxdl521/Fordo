package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AnnotateRequest;
import com.wenjin.entity.AiJob;
import com.wenjin.support.AiJobErrorCodes;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.AiJobPayloadStore;
import com.wenjin.support.AiJobStatus;
import com.wenjin.support.InMemoryMultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AI 作业 worker：短事务领取 → 事务外执行（AI + Task 10 短写）→ 短事务写结果。
 * <p>特性开关关闭时仍会排空已有 PENDING（只拦截新派发）。
 */
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

    /** 恢复卡住 RUNNING，再领取执行一条。 */
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
        if (job == null) {
            return;
        }
        execute(job);
    }

    void execute(AiJob job) {
        Long jobId = job.getId();
        if (aiJobService.isCancelRequested(jobId)) {
            aiJobService.markCancelled(jobId);
            return;
        }
        // 身份来自 job.ownerId，不读取请求线程 CurrentUser
        try (AiJobService.WorkerPrincipalScope ignored =
                     new AiJobService.WorkerPrincipalScope(job.getOwnerId())) {
            aiJobService.markProgress(jobId, 5);
            if (checkCancel(jobId)) {
                return;
            }
            Object result = runKind(job);
            if (checkCancel(jobId)) {
                return;
            }
            aiJobService.markSucceeded(jobId, result);
        } catch (BusinessException e) {
            String code = mapBusinessError(e);
            log.warn("AI job {} failed: code={} msg={}", jobId, code, e.getMessage());
            aiJobService.markFailed(jobId, code);
        } catch (Exception e) {
            log.error("AI job {} internal error: {}", jobId, e.getMessage());
            aiJobService.markFailed(jobId, AiJobErrorCodes.INTERNAL_ERROR);
        }
    }

    private Object runKind(AiJob job) {
        String kind = job.getKind();
        Map<String, Object> req = aiJobService.parseRequestMap(job.getRequestJson());
        return switch (kind) {
            case AiJobKind.QUESTION_GENERATE -> runGenerate(job, req);
            case AiJobKind.QUESTION_ANNOTATE -> runAnnotate(job);
            case AiJobKind.QUESTION_IMPORT_EXCEL -> runQuestionImportExcel(job, req);
            case AiJobKind.GRAPH_EXTRACT -> runGraphExtract(job, req);
            case AiJobKind.GRAPH_IMPORT_EXCEL -> runGraphImportExcel(job, req);
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "未知作业类型: " + kind);
        };
    }

    private Object runGenerate(AiJob job, Map<String, Object> req) {
        String nodeCode = stringVal(req.get("nodeCode"));
        int count = intVal(req.get("count"), 5);
        aiJobService.markProgress(job.getId(), 20);
        Object result = questionService.generate(job.getCourseId(), nodeCode, count);
        aiJobService.markProgress(job.getId(), 90);
        return result;
    }

    private Object runAnnotate(AiJob job) {
        AnnotateRequest body = aiJobService.parseRequest(job.getRequestJson(), AnnotateRequest.class);
        aiJobService.markProgress(job.getId(), 20);
        Object result = questionService.annotate(job.getCourseId(), body);
        aiJobService.markProgress(job.getId(), 90);
        return result;
    }

    private Object runQuestionImportExcel(AiJob job, Map<String, Object> req) {
        InMemoryMultipartFile file = loadFile(req);
        aiJobService.markProgress(job.getId(), 15);
        Object result = questionBankImportService.importFromExcel(job.getCourseId(), file);
        aiJobService.markProgress(job.getId(), 90);
        return result;
    }

    private Object runGraphExtract(AiJob job, Map<String, Object> req) {
        String courseCode = stringVal(req.get("courseCode"));
        InMemoryMultipartFile file = loadFile(req);
        aiJobService.markProgress(job.getId(), 10);
        Object result = graphExtractReviewService.extractAndStash(courseCode, file);
        aiJobService.markProgress(job.getId(), 90);
        return result;
    }

    private Object runGraphImportExcel(AiJob job, Map<String, Object> req) {
        String courseCode = stringVal(req.get("courseCode"));
        InMemoryMultipartFile file = loadFile(req);
        aiJobService.markProgress(job.getId(), 15);
        Object result = graphImportService.importFromExcel(courseCode, file);
        aiJobService.markProgress(job.getId(), 90);
        return result;
    }

    private InMemoryMultipartFile loadFile(Map<String, Object> req) {
        String hash = stringVal(req.get("fileHash"));
        String fileName = stringVal(req.get("fileName"));
        String contentType = stringVal(req.get("contentType"));
        byte[] bytes = payloadStore.load(hash);
        return new InMemoryMultipartFile("file", fileName, contentType, bytes);
    }

    private boolean checkCancel(Long jobId) {
        if (aiJobService.isCancelRequested(jobId)) {
            aiJobService.markCancelled(jobId);
            return true;
        }
        // 若库中已被标记终态则停
        AiJob latest = aiJobService.getById(jobId);
        return latest != null
                && latest.getStatus() != null
                && AiJobStatus.isTerminal(latest.getStatus());
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

    private static String stringVal(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int intVal(Object v, int defaultVal) {
        if (v == null) {
            return defaultVal;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
