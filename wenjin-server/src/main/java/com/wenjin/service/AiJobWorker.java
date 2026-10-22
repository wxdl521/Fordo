package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.AnnotateRequest;
import com.wenjin.entity.AiJob;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.PrincipalLoader;
import com.wenjin.service.memory.MemoryDistillService;
import com.wenjin.service.memory.SummaryRefreshService;
import com.wenjin.service.multiagent.SupervisorSampleReportService;
import com.wenjin.service.multiagent.TeacherWeeklyReportService;
import com.wenjin.service.rag.DocIngestService;
import com.wenjin.service.rag.RagEmbedService;
import com.wenjin.support.AiJobErrorCodes;
import com.wenjin.support.AiJobHeartbeatScheduler;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.AiJobPayloadStore;
import com.wenjin.support.InMemoryMultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

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
    private final MemoryDistillService memoryDistillService;
    private final SummaryRefreshService summaryRefreshService;
    private final DocIngestService docIngestService;
    private final RagEmbedService ragEmbedService;
    private final TeacherWeeklyReportService teacherWeeklyReportService;
    private final SupervisorSampleReportService supervisorSampleReportService;
    private final PrincipalLoader principalLoader;
    private final FeatureFlagsProperties featureFlags;
    private final AiJobHeartbeatScheduler heartbeatScheduler;
    private final long heartbeatIntervalMs;

    public AiJobWorker(AiJobService aiJobService,
                       AiJobPayloadStore payloadStore,
                       QuestionService questionService,
                       QuestionBankImportService questionBankImportService,
                       GraphExtractReviewService graphExtractReviewService,
                       GraphImportService graphImportService,
                       MemoryDistillService memoryDistillService,
                       SummaryRefreshService summaryRefreshService,
                       DocIngestService docIngestService,
                       RagEmbedService ragEmbedService,
                       TeacherWeeklyReportService teacherWeeklyReportService,
                       SupervisorSampleReportService supervisorSampleReportService,
                       PrincipalLoader principalLoader,
                       FeatureFlagsProperties featureFlags,
                       AiJobHeartbeatScheduler heartbeatScheduler,
                       @Value("${wenjin.jobs.heartbeat-interval-ms:30000}") long heartbeatIntervalMs) {
        this.aiJobService = aiJobService;
        this.payloadStore = payloadStore;
        this.questionService = questionService;
        this.questionBankImportService = questionBankImportService;
        this.graphExtractReviewService = graphExtractReviewService;
        this.graphImportService = graphImportService;
        this.memoryDistillService = memoryDistillService;
        this.summaryRefreshService = summaryRefreshService;
        this.docIngestService = docIngestService;
        this.ragEmbedService = ragEmbedService;
        this.teacherWeeklyReportService = teacherWeeklyReportService;
        this.supervisorSampleReportService = supervisorSampleReportService;
        this.principalLoader = principalLoader;
        this.featureFlags = featureFlags;
        this.heartbeatScheduler = heartbeatScheduler;
        this.heartbeatIntervalMs = Math.max(10L, heartbeatIntervalMs);
    }

    @Scheduled(fixedDelayString = "${wenjin.jobs.poll-interval-ms:2000}")
    public void tick() {
        if (featureFlags == null || !featureFlags.isAiJobDispatchEnabled()) {
            return;
        }
        try {
            aiJobService.recoverStale();
        } catch (Exception e) {
            log.warn("AI job recoverStale failed: {}", e.getMessage());
        }
        try {
            if (docIngestService != null) {
                docIngestService.failOrphanedIngestingSources();
            }
        } catch (Exception e) {
            log.warn("DOC_INGEST orphan source sweep failed: {}", e.getMessage());
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
        Optional<CurrentPrincipal> loadedActor = job.getOwnerId() == null
                ? Optional.empty()
                : principalLoader.load(job.getOwnerId());
        if (loadedActor.isEmpty() || !actorAllowed(job.getKind(), loadedActor.get())) {
            aiJobService.markFailed(jobId, leaseToken, AiJobErrorCodes.JOB_ACTOR_REVOKED);
            return;
        }
        HeartbeatGuard heartbeat = new HeartbeatGuard(jobId, leaseToken);
        try (AiJobService.WorkerPrincipalScope ignored =
                     new AiJobService.WorkerPrincipalScope(loadedActor.get());
             heartbeat) {
            heartbeat.start();
            if (checkCancelOrLeaseLoss(jobId, leaseToken, heartbeat)) {
                return;
            }
            markProgressOrLose(jobId, leaseToken, 5, heartbeat);
            if (checkCancelOrLeaseLoss(jobId, leaseToken, heartbeat)) {
                return;
            }
            heartbeat.requireOwned();
            if (!aiJobService.markEffectStarted(jobId, leaseToken)) {
                return;
            }
            heartbeat.requireOwned();
            Object result = runKind(job, leaseToken, heartbeat);
            if (checkCancelOrLeaseLoss(jobId, leaseToken, heartbeat)) {
                return;
            }
            heartbeat.requireOwned();
            aiJobService.markSucceeded(jobId, leaseToken, result);
        } catch (LeaseLostException e) {
            log.info("AI job {} stopped after lease loss", jobId);
        } catch (BusinessException e) {
            String code = mapBusinessError(e);
            log.warn("AI job {} failed: code={} msg={}", jobId, code, e.getMessage());
            if (!heartbeat.isLeaseLost()) {
                aiJobService.markFailed(jobId, leaseToken, code);
            }
        } catch (Exception e) {
            log.error("AI job {} internal error: {}", jobId, e.getMessage());
            if (!heartbeat.isLeaseLost()) {
                aiJobService.markFailed(jobId, leaseToken, AiJobErrorCodes.INTERNAL_ERROR);
            }
        }
    }

    private Object runKind(AiJob job, String leaseToken, HeartbeatGuard heartbeat) {
        String kind = job.getKind();
        Map<String, Object> request = aiJobService.parseRequestMap(job.getRequestJson());
        return switch (kind) {
            case AiJobKind.QUESTION_GENERATE -> runGenerate(job, leaseToken, request, heartbeat);
            case AiJobKind.QUESTION_ANNOTATE -> runAnnotate(job, leaseToken, heartbeat);
            case AiJobKind.QUESTION_IMPORT_EXCEL ->
                    runQuestionImportExcel(job, leaseToken, request, heartbeat);
            case AiJobKind.GRAPH_EXTRACT -> runGraphExtract(job, leaseToken, request, heartbeat);
            case AiJobKind.GRAPH_IMPORT_EXCEL ->
                    runGraphImportExcel(job, leaseToken, request, heartbeat);
            case AiJobKind.MEMORY_DISTILL -> runMemoryDistill(job, leaseToken, request, heartbeat);
            case AiJobKind.SUMMARY_REFRESH -> runSummaryRefresh(job, leaseToken, request, heartbeat);
            case AiJobKind.DOC_INGEST -> runDocIngest(job, leaseToken, request, heartbeat);
            case AiJobKind.RAG_EMBED -> runRagEmbed(job, leaseToken, request, heartbeat);
            case AiJobKind.TEACHER_WEEKLY_REPORT -> runTeacherWeeklyReport(job, leaseToken, request, heartbeat);
            case AiJobKind.SUPERVISOR_SAMPLE -> runSupervisorSample(job, leaseToken, request, heartbeat);
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "未知作业类型: " + kind);
        };
    }

    private Object runTeacherWeeklyReport(AiJob job,
                                          String leaseToken,
                                          Map<String, Object> request,
                                          HeartbeatGuard heartbeat) {
        assertMultiAgentEnabled();
        long courseId = job.getCourseId() != null
                ? job.getCourseId()
                : MemoryDistillService.longVal(request, "courseId", 0L);
        LocalDate periodStart = parseDate(request.get("periodStart"));
        LocalDate periodEnd = parseDate(request.get("periodEnd"));
        markProgressOrLose(job.getId(), leaseToken, 20, heartbeat);
        heartbeat.requireOwned();
        // 骨架：空聚合仍 READY；后续可接掌握度/练习查询端口
        long reportId = teacherWeeklyReportService.buildEmptySkeleton(courseId, periodStart, periodEnd);
        markProgressOrLose(job.getId(), leaseToken, 90, heartbeat);
        return Map.of("reportId", reportId);
    }

    private Object runSupervisorSample(AiJob job,
                                      String leaseToken,
                                      Map<String, Object> request,
                                      HeartbeatGuard heartbeat) {
        assertMultiAgentEnabled();
        long courseId = job.getCourseId() != null
                ? job.getCourseId()
                : MemoryDistillService.longVal(request, "courseId", 0L);
        LocalDate periodStart = parseDate(request.get("periodStart"));
        LocalDate periodEnd = parseDate(request.get("periodEnd"));
        markProgressOrLose(job.getId(), leaseToken, 20, heartbeat);
        heartbeat.requireOwned();
        long reportId = supervisorSampleReportService.buildAndStore(courseId, periodStart, periodEnd);
        markProgressOrLose(job.getId(), leaseToken, 90, heartbeat);
        return Map.of("reportId", reportId);
    }

    private void assertMultiAgentEnabled() {
        if (featureFlags == null || !featureFlags.isMultiAgentEnabled()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "多智能体功能未开启");
        }
    }

    private static LocalDate parseDate(Object value) {
        if (value == null) {
            return LocalDate.now();
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private Object runDocIngest(AiJob job,
                               String leaseToken,
                               Map<String, Object> request,
                               HeartbeatGuard heartbeat) {
        long sourceId = MemoryDistillService.longVal(request, "sourceId", 0L);
        markProgressOrLose(job.getId(), leaseToken, 15, heartbeat);
        heartbeat.requireOwned();
        String hash = stringVal(request.get("fileHash"));
        String fileName = stringVal(request.get("fileName"));
        try {
            byte[] bytes = payloadStore.load(hash);
            markProgressOrLose(job.getId(), leaseToken, 40, heartbeat);
            heartbeat.requireOwned();
            // 按 source.kind 分派：NODE_REF → 原路径；COURSEWARE/TEXTBOOK → 文件入库 + 自动初标
            Object result = docIngestService.ingestSource(sourceId, bytes, fileName);
            markProgressOrLose(job.getId(), leaseToken, 90, heartbeat);
            return result;
        } catch (LeaseLostException e) {
            throw e;
        } catch (RuntimeException e) {
            try {
                docIngestService.markFailed(sourceId);
            } catch (RuntimeException markErr) {
                log.warn("DOC_INGEST markFailed sourceId={} failed: {}", sourceId, markErr.getMessage());
            }
            throw e;
        }
    }

    /** R2：来源切块向量化；批间上报进度（续租 + 租约丢失即停）；向量模式关时 SKIPPED 成功。 */
    private Object runRagEmbed(AiJob job,
                               String leaseToken,
                               Map<String, Object> request,
                               HeartbeatGuard heartbeat) {
        long sourceId = MemoryDistillService.longVal(request, "sourceId", 0L);
        markProgressOrLose(job.getId(), leaseToken, 10, heartbeat);
        heartbeat.requireOwned();
        Object result = ragEmbedService.embedSource(sourceId, job.getCourseId(),
                p -> markProgressOrLose(job.getId(), leaseToken, Math.min(90, Math.max(10, p)), heartbeat));
        markProgressOrLose(job.getId(), leaseToken, 95, heartbeat);
        return result;
    }

    private Object runMemoryDistill(AiJob job,
                                    String leaseToken,
                                    Map<String, Object> request,
                                    HeartbeatGuard heartbeat) {
        long conversationId = MemoryDistillService.longVal(request, "conversationId", 0L);
        long throughId = MemoryDistillService.longVal(request, "throughId", 0L);
        markProgressOrLose(job.getId(), leaseToken, 20, heartbeat);
        heartbeat.requireOwned();
        Object result = memoryDistillService.distill(conversationId, throughId);
        markProgressOrLose(job.getId(), leaseToken, 90, heartbeat);
        return result;
    }

    private Object runSummaryRefresh(AiJob job,
                                     String leaseToken,
                                     Map<String, Object> request,
                                     HeartbeatGuard heartbeat) {
        long conversationId = MemoryDistillService.longVal(request, "conversationId", 0L);
        long expectedThrough = MemoryDistillService.longVal(request, "expectedThrough", 0L);
        markProgressOrLose(job.getId(), leaseToken, 20, heartbeat);
        heartbeat.requireOwned();
        Object result = summaryRefreshService.refresh(conversationId, expectedThrough);
        markProgressOrLose(job.getId(), leaseToken, 90, heartbeat);
        return result;
    }

    /** 教师作业需 teacher/admin；学生侧 MEMORY_DISTILL / SUMMARY_REFRESH 允许任意有效主体。 */
    private static boolean actorAllowed(String kind, CurrentPrincipal principal) {
        if (AiJobKind.isStudentSideJob(kind)) {
            return principal != null;
        }
        return principal != null && (principal.isTeacher() || principal.isAdmin());
    }

    private Object runGenerate(AiJob job,
                               String leaseToken,
                               Map<String, Object> request,
                               HeartbeatGuard heartbeat) {
        String nodeCode = stringVal(request.get("nodeCode"));
        int count = intVal(request.get("count"), 5);
        markProgressOrLose(job.getId(), leaseToken, 20, heartbeat);
        heartbeat.requireOwned();
        Object result = questionService.generate(job.getCourseId(), nodeCode, count);
        markProgressOrLose(job.getId(), leaseToken, 90, heartbeat);
        return result;
    }

    private Object runAnnotate(AiJob job, String leaseToken, HeartbeatGuard heartbeat) {
        AnnotateRequest body = aiJobService.parseRequest(job.getRequestJson(), AnnotateRequest.class);
        markProgressOrLose(job.getId(), leaseToken, 20, heartbeat);
        heartbeat.requireOwned();
        Object result = questionService.annotate(job.getCourseId(), body);
        markProgressOrLose(job.getId(), leaseToken, 90, heartbeat);
        return result;
    }

    private Object runQuestionImportExcel(AiJob job,
                                          String leaseToken,
                                          Map<String, Object> request,
                                          HeartbeatGuard heartbeat) {
        InMemoryMultipartFile file = loadFile(request);
        markProgressOrLose(job.getId(), leaseToken, 15, heartbeat);
        heartbeat.requireOwned();
        Object result = questionBankImportService.importFromExcel(job.getCourseId(), file);
        markProgressOrLose(job.getId(), leaseToken, 90, heartbeat);
        return result;
    }

    private Object runGraphExtract(AiJob job,
                                   String leaseToken,
                                   Map<String, Object> request,
                                   HeartbeatGuard heartbeat) {
        String courseCode = stringVal(request.get("courseCode"));
        InMemoryMultipartFile file = loadFile(request);
        markProgressOrLose(job.getId(), leaseToken, 10, heartbeat);
        heartbeat.requireOwned();
        Object result = graphExtractReviewService.extractAndStash(courseCode, file);
        markProgressOrLose(job.getId(), leaseToken, 90, heartbeat);
        return result;
    }

    private Object runGraphImportExcel(AiJob job,
                                       String leaseToken,
                                       Map<String, Object> request,
                                       HeartbeatGuard heartbeat) {
        String courseCode = stringVal(request.get("courseCode"));
        InMemoryMultipartFile file = loadFile(request);
        markProgressOrLose(job.getId(), leaseToken, 15, heartbeat);
        heartbeat.requireOwned();
        Object result = graphImportService.importFromExcel(courseCode, file);
        markProgressOrLose(job.getId(), leaseToken, 90, heartbeat);
        return result;
    }

    private InMemoryMultipartFile loadFile(Map<String, Object> request) {
        String hash = stringVal(request.get("fileHash"));
        String fileName = stringVal(request.get("fileName"));
        String contentType = stringVal(request.get("contentType"));
        byte[] bytes = payloadStore.load(hash);
        return new InMemoryMultipartFile("file", fileName, contentType, bytes);
    }

    private boolean checkCancelOrLeaseLoss(Long jobId,
                                           String leaseToken,
                                           HeartbeatGuard heartbeat) {
        if (heartbeat.isLeaseLost()) {
            return true;
        }
        if (aiJobService.isCancelRequested(jobId, leaseToken)) {
            if (!heartbeat.isLeaseLost()) {
                aiJobService.markCancelled(jobId, leaseToken);
            }
            return true;
        }
        return heartbeat.isLeaseLost() || !aiJobService.isCurrentLease(jobId, leaseToken);
    }

    private void markProgressOrLose(Long jobId,
                                    String leaseToken,
                                    int progress,
                                    HeartbeatGuard heartbeat) {
        heartbeat.requireOwned();
        if (!aiJobService.markProgress(jobId, leaseToken, progress)) {
            throw new LeaseLostException();
        }
        heartbeat.requireOwned();
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

    private final class HeartbeatGuard implements AutoCloseable {
        private final Long jobId;
        private final String leaseToken;
        private volatile boolean leaseLost;
        private boolean stopped;
        private ScheduledFuture<?> future;

        private HeartbeatGuard(Long jobId, String leaseToken) {
            this.jobId = jobId;
            this.leaseToken = leaseToken;
        }

        private void start() {
            ScheduledFuture<?> scheduled = heartbeatScheduler.scheduleAtFixedRate(
                    this::pulse, heartbeatIntervalMs);
            synchronized (this) {
                future = scheduled;
                if (scheduled == null) {
                    leaseLost = true;
                    stopped = true;
                } else if (stopped) {
                    scheduled.cancel(false);
                }
            }
        }

        private void pulse() {
            synchronized (this) {
                if (stopped) {
                    return;
                }
            }
            boolean extended;
            try {
                extended = aiJobService.heartbeat(jobId, leaseToken);
            } catch (RuntimeException e) {
                log.warn("AI job {} heartbeat failed: {}", jobId, e.getMessage());
                return;
            }
            if (!extended) {
                loseLease();
            }
        }

        private void requireOwned() {
            if (leaseLost) {
                throw new LeaseLostException();
            }
        }

        private boolean isLeaseLost() {
            return leaseLost;
        }

        private synchronized void loseLease() {
            leaseLost = true;
            stop();
        }

        private synchronized void stop() {
            if (stopped) {
                return;
            }
            stopped = true;
            if (future != null) {
                future.cancel(false);
            }
        }

        @Override
        public void close() {
            stop();
        }
    }

    private static final class LeaseLostException extends RuntimeException {
        private LeaseLostException() {
            super(null, null, false, false);
        }
    }
}
