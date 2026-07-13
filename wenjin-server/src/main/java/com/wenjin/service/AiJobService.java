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
import java.util.UUID;

/**
 * AI 作业：派发（幂等）、查询、取消、领取与结果写回。
 * <p>Worker 执行身份取自 {@link AiJob#getOwnerId()}，禁止依赖请求线程 CurrentUser 泄漏。
 */
@Service
public class AiJobService {

    private static final Logger log = LoggerFactory.getLogger(AiJobService.class);

    /** Initial duration granted to each claimed worker lease. */
    public static final int LEASE_MINUTES = 15;
    /** 最大领取次数（含首次与恢复后重领） */
    public static final int MAX_ATTEMPTS = 3;

    private final AiJobMapper aiJobMapper;
    private final CourseAccessService courseAccessService;
    private final FeatureFlagsProperties featureFlags;
    private final ObjectMapper objectMapper;

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
        LocalDateTime now = LocalDateTime.now();
        if (aiJobMapper.cancelPending(jobId, now) == 0) {
            // A claim can win between the ownership read and the pending CAS.
            aiJobMapper.requestRunningCancel(jobId, now);
        }
        AiJob latest = aiJobMapper.selectById(jobId);
        return toVo(latest == null ? job : latest);
    }

    public boolean isCancelRequested(Long jobId, String leaseToken) {
        return validLease(jobId, leaseToken)
                && aiJobMapper.countCancelRequestedForLease(jobId, leaseToken) == 1;
    }

    public boolean isCurrentLease(Long jobId, String leaseToken) {
        return validLease(jobId, leaseToken)
                && aiJobMapper.countCurrentLease(jobId, leaseToken) == 1;
    }

    @Transactional
    public boolean heartbeat(Long jobId, String leaseToken) {
        if (!validLease(jobId, leaseToken)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return aiJobMapper.heartbeat(jobId, leaseToken, now, now.plusMinutes(LEASE_MINUTES)) == 1;
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
        LocalDateTime now = LocalDateTime.now();
        if (attempts >= MAX_ATTEMPTS) {
            aiJobMapper.failPendingMaxAttempts(job.getId(), MAX_ATTEMPTS, now);
            return null;
        }
        String leaseToken = UUID.randomUUID().toString();
        LocalDateTime leaseUntil = now.plusMinutes(LEASE_MINUTES);
        if (aiJobMapper.claimPending(job.getId(), leaseToken, now, leaseUntil, MAX_ATTEMPTS) != 1) {
            return null;
        }
        job.setStatus(AiJobStatus.RUNNING);
        job.setAttempts(attempts + 1);
        job.setStartedAt(now);
        job.setProgress(0);
        job.setResultJson(null);
        job.setErrorCode(null);
        job.setFinishedAt(null);
        job.setLeaseToken(leaseToken);
        job.setLeaseUntil(leaseUntil);
        job.setHeartbeatAt(now);
        job.setEffectStartedAt(null);
        return job;
    }

    /**
     * 恢复卡住的 RUNNING：&gt;15 分钟且 attempts&lt;3 → PENDING；attempts≥3 → FAILED。
     */
    @Transactional
    public int recoverStale() {
        LocalDateTime now = LocalDateTime.now();
        int unknown = aiJobMapper.failExpiredEffectStarted(now);
        int exhausted = aiJobMapper.failExpiredPreEffectExhausted(MAX_ATTEMPTS, now);
        int requeued = aiJobMapper.requeueExpiredPreEffect(MAX_ATTEMPTS, now);
        int touched = unknown + exhausted + requeued;
        if (touched > 0) {
            log.info("AI job lease recovery: unknown={}, exhausted={}, requeued={}",
                    unknown, exhausted, requeued);
        }
        return touched;
    }

    @Transactional
    public boolean markProgress(Long jobId, String leaseToken, int progress) {
        if (!validLease(jobId, leaseToken)) {
            return false;
        }
        int bounded = Math.max(0, Math.min(100, progress));
        return aiJobMapper.updateProgress(jobId, leaseToken, bounded) == 1;
    }

    @Transactional
    public boolean markEffectStarted(Long jobId, String leaseToken) {
        if (!validLease(jobId, leaseToken)) {
            return false;
        }
        return aiJobMapper.markEffectStarted(jobId, leaseToken, LocalDateTime.now()) == 1;
    }

    @Transactional
    public boolean markSucceeded(Long jobId, String leaseToken, Object result) {
        if (!validLease(jobId, leaseToken)) {
            return false;
        }
        return aiJobMapper.markSucceeded(
                jobId, leaseToken, toJson(result), LocalDateTime.now()) == 1;
    }

    @Transactional
    public boolean markFailed(Long jobId, String leaseToken, String errorCode) {
        if (!validLease(jobId, leaseToken)) {
            return false;
        }
        String code = StringUtils.hasText(errorCode) ? errorCode : AiJobErrorCodes.INTERNAL_ERROR;
        return aiJobMapper.markFailed(jobId, leaseToken, code, LocalDateTime.now()) == 1;
    }

    @Transactional
    public boolean markCancelled(Long jobId, String leaseToken) {
        if (!validLease(jobId, leaseToken)) {
            return false;
        }
        return aiJobMapper.markCancelled(jobId, leaseToken, LocalDateTime.now()) == 1;
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

    private static boolean validLease(Long jobId, String leaseToken) {
        return jobId != null && jobId > 0 && StringUtils.hasText(leaseToken);
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
