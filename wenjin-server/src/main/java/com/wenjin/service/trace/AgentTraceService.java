package com.wenjin.service.trace;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.config.RequestIdFilter;
import com.wenjin.entity.AgentRun;
import com.wenjin.entity.AgentStep;
import com.wenjin.mapper.AgentRunMapper;
import com.wenjin.mapper.AgentStepMapper;
import com.wenjin.support.AgentRunStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Agent 执行轨迹写入服务。
 * <p>所有写库方法使用 {@code REQUIRES_NEW}：业务回滚不抹 trace，trace 失败不碰业务。
 * 任何异常均 catch 后 {@code log.warn}，绝不上抛。
 */
@Service
public class AgentTraceService {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceService.class);

    /** I/O 超限截断标记 */
    public static final String TRUNCATED_MARKER = "[truncated]";

    /** input_digest 最大长度 */
    public static final int INPUT_DIGEST_MAX = 200;

    private final AgentRunMapper agentRunMapper;
    private final AgentStepMapper agentStepMapper;
    private final FeatureFlagsProperties featureFlags;
    private final LimitsProperties limits;

    public AgentTraceService(AgentRunMapper agentRunMapper,
                             AgentStepMapper agentStepMapper,
                             FeatureFlagsProperties featureFlags,
                             LimitsProperties limits) {
        this.agentRunMapper = agentRunMapper;
        this.agentStepMapper = agentStepMapper;
        this.featureFlags = featureFlags;
        this.limits = limits == null ? new LimitsProperties() : limits;
    }

    /**
     * 建 run 并返回显式 {@link TraceContext}。
     * toggle 关闭或写入失败时返回共享 {@link TraceContext#NOOP}。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TraceContext startRun(StartRunCmd cmd) {
        if (cmd == null) {
            return TraceContext.NOOP;
        }
        if (featureFlags == null || !featureFlags.isAgentTraceEnabled()) {
            return TraceContext.NOOP;
        }
        try {
            AgentRun run = new AgentRun();
            run.setRequestId(resolveRequestId(cmd));
            run.setStudentId(cmd.studentId());
            run.setCourseId(cmd.courseId());
            run.setConversationId(cmd.conversationId());
            run.setKind(cmd.kind());
            run.setStatus(AgentRunStatus.RUNNING);
            run.setModel(cmd.model());
            run.setInputDigest(toInputDigest(cmd.firstInput()));
            run.setTokensIn(0);
            run.setTokensOut(0);
            run.setTokensEstimated(0);
            run.setCreatedAt(LocalDateTime.now());

            agentRunMapper.insert(run);
            if (run.getId() == null) {
                log.warn("agent_run insert returned null id; falling back to NOOP");
                return TraceContext.NOOP;
            }
            return TraceContext.active(run.getId());
        } catch (Exception e) {
            log.warn("startRun failed, returning NOOP: {}", e.toString());
            return TraceContext.NOOP;
        }
    }

    /**
     * 写一条 step：seq 自增，I/O 按 limits 截断，token 聚合进 ctx。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void step(TraceContext ctx, StepRecord rec) {
        if (ctx == null || ctx.isNoop() || rec == null) {
            return;
        }
        try {
            int seq = ctx.nextSeq();
            int in = Math.max(0, rec.tokensIn());
            int out = Math.max(0, rec.tokensOut());
            ctx.addTokens(in, out);

            AgentStep step = new AgentStep();
            step.setRunId(ctx.runId());
            step.setSeq(seq);
            step.setStepType(rec.stepType());
            step.setName(rec.name());
            step.setStatus(rec.status());
            step.setInputJson(truncateIo(rec.inputJson()));
            step.setOutputJson(truncateIo(rec.outputJson()));
            step.setTokensIn(in);
            step.setTokensOut(out);
            step.setDurationMs(rec.durationMs());
            step.setErrorCode(rec.errorCode());
            step.setCreatedAt(LocalDateTime.now());

            agentStepMapper.insert(step);
        } catch (Exception e) {
            log.warn("step failed for runId={}: {}", ctx.runId(), e.toString());
        }
    }

    /**
     * 结束 run：写 status / errorCode / 聚合 token / duration / finished_at。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishRun(TraceContext ctx, int runStatus, String errorCode) {
        if (ctx == null || ctx.isNoop()) {
            return;
        }
        try {
            AgentRun patch = new AgentRun();
            patch.setId(ctx.runId());
            patch.setStatus(runStatus);
            patch.setErrorCode(errorCode);
            patch.setTokensIn(ctx.tokensIn());
            patch.setTokensOut(ctx.tokensOut());
            long durationMs = Duration.between(ctx.startedAt(), Instant.now()).toMillis();
            if (durationMs < 0) {
                durationMs = 0;
            }
            // INT 列；极端时钟回拨或超长 run 夹紧到 Integer.MAX_VALUE
            patch.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
            patch.setFinishedAt(LocalDateTime.now());

            agentRunMapper.updateById(patch);
        } catch (Exception e) {
            log.warn("finishRun failed for runId={}: {}", ctx.runId(), e.toString());
        }
    }

    /** 截断 I/O 字符串；超出 {@code wenjin.limits.trace.max-io-chars} 追加 {@link #TRUNCATED_MARKER}。 */
    public String truncateIo(String value) {
        if (value == null) {
            return null;
        }
        int max = limits.getTrace() == null ? 8000 : limits.getTrace().getMaxIoChars();
        if (max <= 0 || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + TRUNCATED_MARKER;
    }

    static String toInputDigest(String firstInput) {
        if (firstInput == null) {
            return null;
        }
        if (firstInput.length() <= INPUT_DIGEST_MAX) {
            return firstInput;
        }
        return firstInput.substring(0, INPUT_DIGEST_MAX);
    }

    private String resolveRequestId(StartRunCmd cmd) {
        if (cmd.requestId() != null && !cmd.requestId().isBlank()) {
            return trimRequestId(cmd.requestId());
        }
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletAttrs) {
                HttpServletRequest request = servletAttrs.getRequest();
                return trimRequestId(RequestIdFilter.currentRequestId(request));
            }
        } catch (Exception e) {
            log.debug("resolveRequestId from request context failed: {}", e.toString());
        }
        return null;
    }

    private static String trimRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        String trimmed = requestId.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }
}
