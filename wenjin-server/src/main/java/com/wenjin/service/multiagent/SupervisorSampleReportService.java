package com.wenjin.service.multiagent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.AgentReport;
import com.wenjin.entity.AgentRun;
import com.wenjin.eval.judge.JudgeService;
import com.wenjin.mapper.AgentRunMapper;
import com.wenjin.support.AgentReportKind;
import com.wenjin.support.AgentReportStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Supervisor 抽查报告：抽样 + Judge + 落库 READY。
 * <p>JudgeService 可选；缺省时 flags 为空仍 READY（骨架降级）。
 */
@Service
public class SupervisorSampleReportService {

    private static final Logger log = LoggerFactory.getLogger(SupervisorSampleReportService.class);

    private final AgentReportService agentReportService;
    private final AgentRunMapper agentRunMapper;
    private final LimitsProperties limitsProperties;
    private final JudgeService judgeService;
    private final Random random;

    @Autowired
    public SupervisorSampleReportService(
            AgentReportService agentReportService,
            AgentRunMapper agentRunMapper,
            LimitsProperties limitsProperties,
            ObjectProvider<JudgeService> judgeServiceProvider
    ) {
        this(agentReportService, agentRunMapper, limitsProperties,
                judgeServiceProvider == null ? null : judgeServiceProvider.getIfAvailable(),
                new Random());
    }

    /** 可注入 Random 便于单测。 */
    public SupervisorSampleReportService(
            AgentReportService agentReportService,
            AgentRunMapper agentRunMapper,
            LimitsProperties limitsProperties,
            JudgeService judgeService,
            Random random
    ) {
        this.agentReportService = Objects.requireNonNull(agentReportService, "agentReportService");
        this.agentRunMapper = Objects.requireNonNull(agentRunMapper, "agentRunMapper");
        this.limitsProperties = limitsProperties;
        this.judgeService = judgeService;
        this.random = random == null ? new Random() : random;
    }

    public long buildAndStore(long courseId, LocalDate periodStart, LocalDate periodEnd) {
        AgentReport report = agentReportService.ensurePending(
                courseId, AgentReportKind.SUPERVISOR_SAMPLE, periodStart, periodEnd);
        if (report.getStatus() != null && report.getStatus() == AgentReportStatus.READY
                && report.getPayloadJson() != null && !report.getPayloadJson().isBlank()) {
            return report.getId();
        }

        int sampleSize = sampleSize();
        List<AgentRun> candidates = loadCandidates(courseId, periodStart, periodEnd);
        List<AgentRun> sampled = sampleEligible(candidates, sampleSize);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("narrative", "");

        if (judgeService == null) {
            log.debug("JudgeService absent; supervisor sample empty flags courseId={}", courseId);
            payload.put("sampledRunIds", sampled.stream().map(AgentRun::getId).toList());
            payload.put("flags", List.of());
            payload.put("judgePromptVersion", JudgeService.PROMPT_VERSION);
            payload.put("lowScoreThreshold", SupervisorSampler.DEFAULT_LOW_SCORE_THRESHOLD);
        } else {
            SupervisorSampler sampler = new SupervisorSampler(judgeService, random);
            // 已抽样；evaluate 不再二次 shuffle
            Map<String, Object> eval = sampler.evaluate(
                    sampled,
                    id -> "run:" + id,
                    id -> "");
            payload.putAll(eval);
        }

        agentReportService.markReady(report.getId(), payload);
        return report.getId();
    }

    private List<AgentRun> sampleEligible(List<AgentRun> candidates, int n) {
        List<AgentRun> pool = new ArrayList<>(SupervisorSampler.eligible(candidates));
        if (pool.isEmpty() || n <= 0) {
            return List.of();
        }
        Collections.shuffle(pool, random);
        if (pool.size() <= n) {
            return List.copyOf(pool);
        }
        return List.copyOf(pool.subList(0, n));
    }

    private List<AgentRun> loadCandidates(long courseId, LocalDate periodStart, LocalDate periodEnd) {
        LocalDateTime from = periodStart.atStartOfDay();
        LocalDateTime to = periodEnd.atTime(LocalTime.MAX);
        return agentRunMapper.selectList(new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getCourseId, courseId)
                .eq(AgentRun::getKind, SupervisorSampler.KIND_COMPANION_CHAT)
                .ge(AgentRun::getCreatedAt, from)
                .le(AgentRun::getCreatedAt, to));
    }

    private int sampleSize() {
        if (limitsProperties != null && limitsProperties.getSupervisor() != null) {
            return Math.max(1, limitsProperties.getSupervisor().getSampleSize());
        }
        return 5;
    }
}
