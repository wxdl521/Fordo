package com.wenjin.service.plan;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.entity.AgentPlan;
import com.wenjin.service.trace.AgentTraceService;
import com.wenjin.service.trace.StartRunCmd;
import com.wenjin.service.trace.TraceContext;
import com.wenjin.support.AgentRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * 每日 re-plan：仅处理 ACTIVE 计划；单计划失败隔离。
 * <p>DRAFT 永不触碰。Clock 可注入便于单测。
 */
@Component
public class PlanReplanScheduler {

    private static final Logger log = LoggerFactory.getLogger(PlanReplanScheduler.class);

    private final PlanServiceImpl planService;
    private final FeatureFlagsProperties featureFlags;
    private final Clock clock;

    @Autowired(required = false)
    private AgentTraceService agentTraceService;

    public PlanReplanScheduler(PlanServiceImpl planService,
                               FeatureFlagsProperties featureFlags,
                               Clock clock) {
        this.planService = Objects.requireNonNull(planService, "planService");
        this.featureFlags = featureFlags;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Scheduled(cron = "${wenjin.plan.replan-cron:0 30 4 * * *}")
    public void scheduledReplan() {
        replanAllActive();
    }

    /**
     * 处理全部 ACTIVE 计划；供调度与单测调用。
     *
     * @return 成功处理的计划数
     */
    public int replanAllActive() {
        if (featureFlags != null && !featureFlags.isPlanEnabled()) {
            log.debug("plan-enabled=false; skip replan");
            return 0;
        }
        List<AgentPlan> actives = planService.listActivePlans();
        if (actives == null || actives.isEmpty()) {
            return 0;
        }
        int ok = 0;
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
        for (AgentPlan plan : actives) {
            try {
                processOne(plan, today);
                ok++;
            } catch (Exception e) {
                log.warn("replan failed planId={} studentId={}: {}",
                        plan.getId(), plan.getStudentId(), e.toString());
            }
        }
        return ok;
    }

    void processOne(AgentPlan plan, LocalDate today) {
        if (plan == null) {
            return;
        }
        // 硬断言：DRAFT 不进此路径（listActivePlans 已过滤）
        TraceContext ctx = TraceContext.NOOP;
        if (agentTraceService != null) {
            ctx = agentTraceService.startRun(StartRunCmd.of(
                    plan.getStudentId(),
                    plan.getCourseId(),
                    null,
                    "PLAN_REPLAN",
                    null,
                    "replan@" + today));
        }
        int status = AgentRunStatus.SUCCEEDED;
        String err = null;
        try {
            planService.replanOne(plan);
        } catch (RuntimeException ex) {
            status = AgentRunStatus.FAILED;
            err = "REPLAN_FAILED";
            throw ex;
        } finally {
            if (agentTraceService != null) {
                agentTraceService.finishRun(ctx, status, err);
            }
        }
    }
}
