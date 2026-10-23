package com.wenjin.service.multiagent;

import com.wenjin.service.teacher.TeacherAnalyticsService;
import com.wenjin.service.teacher.TeacherAnalyticsService.WeeklyFacts;
import com.wenjin.service.teacher.TeacherAnalyticsService.WindowEngagement;
import com.wenjin.support.multiagent.ReportAggregations.EffectiveDelta;
import com.wenjin.support.multiagent.ReportAggregations.NodeStudentEffective;
import com.wenjin.support.multiagent.ReportAggregations.StudentLastPractice;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 教师周报输入提供者：复用 {@link TeacherAnalyticsService}（花名册=仅活跃选课学生、未归档节点、读时衰减的有效掌握度、
 * 7 天快照对比、最近作答日）和窗口参与度计数，组装 {@link ReportInputs}。
 * <p>
 * 时间约定（均用应用 {@link Clock}，部署 TZ=Asia/Shanghai）：
 * <ul>
 *   <li>本周窗口 = {@code [periodStart 00:00, periodEnd+1 00:00)}；上周窗口 = 紧邻其前的等长 7 天；</li>
 *   <li>掌握度（弱点 / 7 天下降）取报告生成时刻的读时衰减值——调度器在周一凌晨生成上一完整周的报告，二者基本重合；</li>
 *   <li>「14 天无练习」以 {@code periodEnd} 为基准日判定。</li>
 * </ul>
 * 隐私：只读计数与学习数据；不读 student_memory，不读伴侣消息正文。
 */
@Component
public class TeacherWeeklyReportDataProvider {

    private final TeacherAnalyticsService analytics;
    private final Clock clock;

    public TeacherWeeklyReportDataProvider(TeacherAnalyticsService analytics, Clock clock) {
        this.analytics = Objects.requireNonNull(analytics, "analytics");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 周报聚合输入。
     *
     * @param rosterSize 选课（活跃）学生数
     * @param thisWeek   本周参与度
     * @param lastWeek   上周参与度
     */
    public record ReportInputs(
            int rosterSize,
            List<NodeStudentEffective> masteryRows,
            List<EffectiveDelta> deltas,
            List<StudentLastPractice> practices,
            Map<String, String> nodeNames,
            Map<Long, String> studentNames,
            WindowEngagement thisWeek,
            WindowEngagement lastWeek
    ) {
        public static ReportInputs empty() {
            WindowEngagement zero = new WindowEngagement(0, 0, 0);
            return new ReportInputs(0, List.of(), List.of(), List.of(), Map.of(), Map.of(), zero, zero);
        }
    }

    public ReportInputs collect(long courseId, LocalDate periodStart, LocalDate periodEnd) {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        LocalDateTime weekFrom = periodStart.atStartOfDay();
        LocalDateTime weekTo = periodEnd.plusDays(1).atStartOfDay();
        LocalDateTime prevFrom = weekFrom.minusDays(7);

        WeeklyFacts facts = analytics.weeklyFacts(courseId, LocalDateTime.now(clock));
        if (facts.rosterSize() == 0) {
            return ReportInputs.empty();
        }
        WindowEngagement thisWeek = analytics.windowEngagement(courseId, weekFrom, weekTo);
        WindowEngagement lastWeek = analytics.windowEngagement(courseId, prevFrom, weekFrom);
        return new ReportInputs(facts.rosterSize(), facts.masteryRows(), facts.deltas(), facts.practices(),
                facts.nodeNames(), facts.studentNames(), thisWeek, lastWeek);
    }
}
