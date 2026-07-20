package com.wenjin.service.multiagent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.entity.Course;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.service.AiJobService;
import com.wenjin.support.AiJobKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 多智能体报告 cron 入队骨架：multi-agent-enabled=false 时零调度。
 * <p>默认周一凌晨；对每门未归档课程入队 TEACHER_WEEKLY_REPORT + SUPERVISOR_SAMPLE。
 * 幂等依赖 ai_job (owner, kind, request_key) 与 agent_report uk_report_period。
 */
@Component
public class MultiAgentReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentReportScheduler.class);

    private final FeatureFlagsProperties featureFlags;
    private final CourseMapper courseMapper;
    private final AiJobService aiJobService;
    private final Clock clock;

    public MultiAgentReportScheduler(FeatureFlagsProperties featureFlags,
                                     CourseMapper courseMapper,
                                     AiJobService aiJobService,
                                     Clock clock) {
        this.featureFlags = featureFlags;
        this.courseMapper = Objects.requireNonNull(courseMapper, "courseMapper");
        this.aiJobService = Objects.requireNonNull(aiJobService, "aiJobService");
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Scheduled(cron = "${wenjin.multi-agent.report-cron:0 15 3 * * MON}")
    public void scheduledEnqueue() {
        enqueueWeeklyReports();
    }

    /**
     * @return 成功入队（或已存在）的 job 数
     */
    public int enqueueWeeklyReports() {
        if (featureFlags == null || !featureFlags.isMultiAgentEnabled()) {
            log.debug("multi-agent-enabled=false; skip report enqueue");
            return 0;
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
        // 上一完整自然周：上周一 ~ 上周日
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate periodStart = thisMonday.minusWeeks(1);
        LocalDate periodEnd = periodStart.plusDays(6);

        List<Course> courses = courseMapper.selectList(new LambdaQueryWrapper<Course>()
                .isNull(Course::getArchivedAt));
        if (courses == null || courses.isEmpty()) {
            return 0;
        }
        int enqueued = 0;
        for (Course c : courses) {
            if (c == null || c.getId() == null) {
                continue;
            }
            long ownerId = c.getTeacherId() == null ? 0L : c.getTeacherId();
            if (ownerId <= 0) {
                log.debug("skip course without teacherId courseId={}", c.getId());
                continue;
            }
            try {
                if (enqueueOne(c.getId(), ownerId, AiJobKind.TEACHER_WEEKLY_REPORT,
                        periodStart, periodEnd)) {
                    enqueued++;
                }
                if (enqueueOne(c.getId(), ownerId, AiJobKind.SUPERVISOR_SAMPLE,
                        periodStart, periodEnd)) {
                    enqueued++;
                }
            } catch (Exception e) {
                log.warn("report enqueue failed courseId={}: {}", c.getId(), e.toString());
            }
        }
        return enqueued;
    }

    private boolean enqueueOne(long courseId,
                               long ownerId,
                               String kind,
                               LocalDate periodStart,
                               LocalDate periodEnd) {
        String requestKey = "course:" + courseId + ":period:" + periodStart;
        Map<String, Object> body = new HashMap<>();
        body.put("courseId", courseId);
        body.put("periodStart", periodStart.toString());
        body.put("periodEnd", periodEnd.toString());
        return aiJobService.enqueueInternal(courseId, ownerId, kind, requestKey, body) != null;
    }
}
