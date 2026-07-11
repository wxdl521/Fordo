package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.wenjin.support.AiJobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 作业：派发（幂等）、查询、取消、领取与结果写回。
 * <p>Worker 执行身份取自 {@link AiJob#getOwnerId()}，禁止依赖请求线程 CurrentUser 泄漏。
 */
@Service
public class AiJobService {

    private static final Logger log = LoggerFactory.getLogger(AiJobService.class);

    /** RUNNING 超过该时长视为卡住，可退回 PENDING */
    public static final int STALE_MINUTES = 15;
    /** 最大领取次数（含首次与恢复后重领） */
    public static final int MAX_ATTEMPTS = 3;

    private final AiJobMapper aiJobMapper;
    private final CourseAccessService courseAccessService;
    private final FeatureFlagsProperties featureFlags;
    private final ObjectMapper objectMapper;

    /** 协作式取消：RUNNING 作业 id 集合（进程内；PENDING 直接改库） */
    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();

    public AiJobService(AiJobMapper aiJobMapper,
                        CourseAccessService courseAccessService,
                        FeatureFlagsProperties featureFlags,
                        ObjectMapper objectMapper) {
        this.aiJobMapper = aiJobMapper;
        this.courseAccessService = courseAccessService;
        this.featureFlags = featureFlags;
        this.objectMapper = objectMapper;
    }

    /**
     * 派发作业。同一 (owner, kind, requestKey) 幂等返回原作业。
     *
     * @param courseId    课程 ID（归属在此校验）
     * @param kind        作业类型
     * @param requestKey  幂等键；空则生成
     * @param requestBody 写入 request_json 的对象
     */
    @Transactional
    public AiJobVO dispatch(Long courseId, String kind, String requestKey, Object requestBody) {
        assertDispatchEnabled();
        courseAccessService.assertManageCourse(courseId);
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        long ownerId = principal.userId();

        String key = normalizeRequestKey(requestKey);
        String json = toJson(requestBody);

        AiJob existing = findByOwnerKindKey(ownerId, kind, key);
        if (existing != null) {
            return toVo(existing);
        }

        AiJob job = new AiJob();
        job.setKind(kind);
        job.setOwnerId(ownerId);
        job.setCourseId(courseId);
        job.setRequestKey(key);
        job.setStatus(AiJobStatus.PENDING);
        job.setProgress(0);
        job.setAttempts(0);
        job.setRequestJson(json);
        job.setCreatedAt(LocalDateTime.now());

        try {
            aiJobMapper.insert(job);
        } catch (DataIntegrityViolationException e) {
            // 并发同键：返回已有行
            AiJob raced = findByOwnerKindKey(ownerId, kind, key);
            if (raced != null) {
                return toVo(raced);
            }
            throw e;
        }
        return toVo(job);
    }

    /**
     * 按 courseCode 解析课程后派发（图谱 extract/import）。
     */
    @Transactional
    public AiJobVO dispatchByCourseCode(String courseCode, String kind, String requestKey, Object requestBody) {
        assertDispatchEnabled();
        Long courseId = courseAccessService.assertManageCourseCode(courseCode);
        // assertManageCourseCode 已校验归属；再走 dispatch 会二次校验——直接内联避免重复 flag 检查外的重复
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        long ownerId = principal.userId();
        String key = normalizeRequestKey(requestKey);
        String json = toJson(requestBody);

        AiJob existing = findByOwnerKindKey(ownerId, kind, key);
        if (existing != null) {
            return toVo(existing);
        }

        AiJob job = new AiJob();
        job.setKind(kind);
        job.setOwnerId(ownerId);
        job.setCourseId(courseId);
        job.setRequestKey(key);
        job.setStatus(AiJobStatus.PENDING);
        job.setProgress(0);
        job.setAttempts(0);
        job.setRequestJson(json);
        job.setCreatedAt(LocalDateTime.now());
        try {
            aiJobMapper.insert(job);
        } catch (DataIntegrityViolationException e) {
            AiJob raced = findByOwnerKindKey(ownerId, kind, key);
            if (raced != null) {
                return toVo(raced);
            }
            throw e;
        }
        return toVo(job);
    }

    /** 发起人（或 ADMIN）可读；跨教师统一 404。 */
    public AiJobVO getForOwner(Long jobId) {
        AiJob job = requireOwnedJob(jobId);
        return toVo(job);
    }

    /**
     * 取消：PENDING 立即 CANCELLED；RUNNING 登记协作取消标志（worker 检查后落库）。
     */
    @Transactional
    public AiJobVO cancel(Long jobId) {
        AiJob job = requireOwnedJob(jobId);
        int status = job.getStatus() == null ? AiJobStatus.PENDING : job.getStatus();
        if (AiJobStatus.isTerminal(status)) {
            return toVo(job);
        }
        if (status == AiJobStatus.PENDING) {
            job.setStatus(AiJobStatus.CANCELLED);
            job.setErrorCode(AiJobErrorCodes.CANCELLED);
            job.setFinishedAt(LocalDateTime.now());
            aiJobMapper.updateById(job);
            cancelRequested.remove(jobId);
            return toVo(job);
        }
        // RUNNING：协作取消
        cancelRequested.add(jobId);
        return toVo(job);
    }

    public boolean isCancelRequested(Long jobId) {
        return jobId != null && cancelRequested.contains(jobId);
    }

    /**
     * 短事务领取：SELECT … FOR UPDATE SKIP LOCKED → RUNNING。
     *
     * @return 已领取作业，或 null（队列空）
     */
    @Transactional
    public AiJob claimNext() {
        AiJob job = aiJobMapper.selectOnePendingForUpdate();
        if (job == null) {
            return null;
        }
        int attempts = job.getAttempts() == null ? 0 : job.getAttempts();
        if (attempts >= MAX_ATTEMPTS) {
            job.setStatus(AiJobStatus.FAILED);
            job.setErrorCode(AiJobErrorCodes.MAX_ATTEMPTS);
            job.setFinishedAt(LocalDateTime.now());
            aiJobMapper.updateById(job);
            return null;
        }
        job.setStatus(AiJobStatus.RUNNING);
        job.setAttempts(attempts + 1);
        job.setStartedAt(LocalDateTime.now());
        job.setProgress(0);
        job.setErrorCode(null);
        job.setFinishedAt(null);
        aiJobMapper.updateById(job);
        return job;
    }

    /**
     * 恢复卡住的 RUNNING：&gt;15 分钟且 attempts&lt;3 → PENDING；attempts≥3 → FAILED。
     */
    @Transactional
    public int recoverStale() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STALE_MINUTES);
        int failed = aiJobMapper.failExhaustedStale(threshold, MAX_ATTEMPTS, LocalDateTime.now());
        int requeued = aiJobMapper.requeueStaleRunning(threshold, MAX_ATTEMPTS);
        if (failed + requeued > 0) {
            log.info("AI job stale recovery: requeued={}, failed={}", requeued, failed);
        }
        return requeued + failed;
    }

    @Transactional
    public void markProgress(Long jobId, int progress) {
        AiJob job = aiJobMapper.selectById(jobId);
        if (job == null || job.getStatus() == null || job.getStatus() != AiJobStatus.RUNNING) {
            return;
        }
        job.setProgress(Math.max(0, Math.min(100, progress)));
        aiJobMapper.updateById(job);
    }

    @Transactional
    public void markSucceeded(Long jobId, Object result) {
        AiJob job = aiJobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        if (job.getStatus() != null && AiJobStatus.isTerminal(job.getStatus())) {
            return;
        }
        job.setStatus(AiJobStatus.SUCCEEDED);
        job.setProgress(100);
        job.setResultJson(toJson(result));
        job.setErrorCode(null);
        job.setFinishedAt(LocalDateTime.now());
        aiJobMapper.updateById(job);
        cancelRequested.remove(jobId);
    }

    @Transactional
    public void markFailed(Long jobId, String errorCode) {
        AiJob job = aiJobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        if (job.getStatus() != null && AiJobStatus.isTerminal(job.getStatus())) {
            return;
        }
        String code = StringUtils.hasText(errorCode) ? errorCode : AiJobErrorCodes.INTERNAL_ERROR;
        job.setStatus(AiJobStatus.FAILED);
        job.setErrorCode(code);
        job.setFinishedAt(LocalDateTime.now());
        aiJobMapper.updateById(job);
        cancelRequested.remove(jobId);
    }

    @Transactional
    public void markCancelled(Long jobId) {
        AiJob job = aiJobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        if (job.getStatus() != null && AiJobStatus.isTerminal(job.getStatus())) {
            cancelRequested.remove(jobId);
            return;
        }
        job.setStatus(AiJobStatus.CANCELLED);
        job.setErrorCode(AiJobErrorCodes.CANCELLED);
        job.setFinishedAt(LocalDateTime.now());
        aiJobMapper.updateById(job);
        cancelRequested.remove(jobId);
    }

    /** 供 worker 使用：不经所有权检查，仅按 id 加载。 */
    public AiJob getById(Long jobId) {
        return aiJobMapper.selectById(jobId);
    }

    public AiJobVO toVo(AiJob job) {
        AiJobVO vo = new AiJobVO();
        vo.setId(job.getId());
        vo.setKind(job.getKind());
        vo.setCourseId(job.getCourseId());
        vo.setRequestKey(job.getRequestKey());
        vo.setStatus(job.getStatus());
        vo.setProgress(job.getProgress());
        vo.setAttempts(job.getAttempts());
        vo.setErrorCode(job.getErrorCode());
        vo.setCreatedAt(job.getCreatedAt());
        vo.setStartedAt(job.getStartedAt());
        vo.setFinishedAt(job.getFinishedAt());
        if (StringUtils.hasText(job.getResultJson())) {
            try {
                vo.setResult(objectMapper.readTree(job.getResultJson()));
            } catch (JsonProcessingException e) {
                vo.setResult(null);
            }
        }
        return vo;
    }

    public Map<String, Object> parseRequestMap(String requestJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(requestJson, Map.class);
            return map;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "作业请求载荷损坏");
        }
    }

    public <T> T parseRequest(String requestJson, Class<T> type) {
        try {
            return objectMapper.readValue(requestJson, type);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "作业请求载荷损坏");
        }
    }

    public JsonNode parseJsonNode(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private void assertDispatchEnabled() {
        if (featureFlags == null || !featureFlags.isAiJobDispatchEnabled()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "AI 异步作业派发暂未开放");
        }
    }

    private AiJob requireOwnedJob(Long jobId) {
        if (jobId == null || jobId <= 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "作业不存在");
        }
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        AiJob job = aiJobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "作业不存在");
        }
        if (principal.isAdmin()) {
            return job;
        }
        if (job.getOwnerId() == null || !job.getOwnerId().equals(principal.userId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "作业不存在");
        }
        return job;
    }

    private AiJob findByOwnerKindKey(long ownerId, String kind, String key) {
        return aiJobMapper.selectOne(new LambdaQueryWrapper<AiJob>()
                .eq(AiJob::getOwnerId, ownerId)
                .eq(AiJob::getKind, kind)
                .eq(AiJob::getRequestKey, key)
                .last("LIMIT 1"));
    }

    private static String normalizeRequestKey(String requestKey) {
        if (!StringUtils.hasText(requestKey)) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        String k = requestKey.trim();
        if (k.length() > 64) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Idempotency-Key 长度不能超过 64");
        }
        return k;
    }

    private String toJson(Object body) {
        if (body == null) {
            return "{}";
        }
        if (body instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "序列化作业请求失败");
        }
    }

    /**
     * Worker 执行期临时挂载 owner 主体，使既有 CourseAccessService 校验通过。
     * 身份来源是 job.ownerId，而非 HTTP 请求 ThreadLocal。
     */
    public static final class WorkerPrincipalScope implements AutoCloseable {
        public WorkerPrincipalScope(long ownerId) {
            CurrentUser.set(new CurrentPrincipal(ownerId, UserRole.TEACHER));
        }

        @Override
        public void close() {
            CurrentUser.clear();
        }
    }
}
