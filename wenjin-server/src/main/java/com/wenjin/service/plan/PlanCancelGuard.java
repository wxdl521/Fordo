package com.wenjin.service.plan;

import com.wenjin.entity.AgentRun;
import com.wenjin.mapper.AgentRunMapper;
import com.wenjin.service.trace.TraceContext;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Run 级转向/取消（M10 薄辅助）。
 * <p>编排循环在 step 边界调用 {@link #isCancelRequested}；已置位则停止后续 LLM/工具。
 * 写入途径：SSE 断连或 {@code POST /api/companion/runs/{id}/cancel}。
 */
@Component
public class PlanCancelGuard {

    private final AgentRunMapper agentRunMapper;
    private final Clock clock;

    public PlanCancelGuard(AgentRunMapper agentRunMapper, Clock clock) {
        this.agentRunMapper = Objects.requireNonNull(agentRunMapper, "agentRunMapper");
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public boolean isCancelRequested(TraceContext ctx) {
        if (ctx == null || ctx.isNoop() || ctx.runId() <= 0) {
            return false;
        }
        return isCancelRequested(ctx.runId());
    }

    public boolean isCancelRequested(Long runId) {
        if (runId == null) {
            return false;
        }
        AgentRun run = agentRunMapper.selectById(runId);
        return run != null && run.getCancelRequestedAt() != null;
    }

    /**
     * 幂等置位 cancel_requested_at（本人校验由调用方保证）。
     *
     * @return true 若写入或已存在
     */
    public boolean requestCancel(Long runId) {
        if (runId == null) {
            return false;
        }
        AgentRun run = agentRunMapper.selectById(runId);
        if (run == null) {
            return false;
        }
        if (run.getCancelRequestedAt() != null) {
            return true;
        }
        AgentRun patch = new AgentRun();
        patch.setId(runId);
        patch.setCancelRequestedAt(LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()));
        agentRunMapper.updateById(patch);
        return true;
    }
}
