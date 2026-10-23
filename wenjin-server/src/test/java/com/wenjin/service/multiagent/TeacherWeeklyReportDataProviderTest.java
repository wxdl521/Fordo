package com.wenjin.service.multiagent;

import com.wenjin.service.multiagent.TeacherWeeklyReportDataProvider.ReportInputs;
import com.wenjin.service.teacher.TeacherAnalyticsService;
import com.wenjin.service.teacher.TeacherAnalyticsService.WeeklyFacts;
import com.wenjin.service.teacher.TeacherAnalyticsService.WindowEngagement;
import com.wenjin.support.multiagent.ReportAggregations.NodeStudentEffective;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeacherWeeklyReportDataProviderTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final TeacherAnalyticsService analytics = mock(TeacherAnalyticsService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-19T19:15:00Z"), ZONE); // Mon 03:15 local
    private final TeacherWeeklyReportDataProvider provider = new TeacherWeeklyReportDataProvider(analytics, clock);

    @Test
    void windowsAreThePeriodAndTheSevenDaysBeforeIt() {
        when(analytics.weeklyFacts(anyLong(), any())).thenReturn(new WeeklyFacts(2,
                List.of(new NodeStudentEffective(1, "N1", 0.4)), List.of(), List.of(),
                Map.of("N1", "循环"), Map.of(1L, "张三")));
        when(analytics.windowEngagement(anyLong(), any(), any())).thenReturn(new WindowEngagement(5, 2, 1));

        ReportInputs in = provider.collect(9L, LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(analytics, org.mockito.Mockito.times(2)).windowEngagement(org.mockito.ArgumentMatchers.eq(9L),
                from.capture(), to.capture());
        // this week: [07-13 00:00, 07-20 00:00); last week: [07-06 00:00, 07-13 00:00)
        assertThat(from.getAllValues()).containsExactly(
                LocalDateTime.of(2026, 7, 13, 0, 0), LocalDateTime.of(2026, 7, 6, 0, 0));
        assertThat(to.getAllValues()).containsExactly(
                LocalDateTime.of(2026, 7, 20, 0, 0), LocalDateTime.of(2026, 7, 13, 0, 0));
        // mastery is read at the app clock "now" (Asia/Shanghai local time)
        verify(analytics).weeklyFacts(9L, LocalDateTime.of(2026, 7, 20, 3, 15));
        assertThat(in.rosterSize()).isEqualTo(2);
        assertThat(in.thisWeek().answers()).isEqualTo(5);
        assertThat(in.nodeNames()).containsEntry("N1", "循环");
    }

    @Test
    void emptyCourseSkipsEngagementQueries() {
        when(analytics.weeklyFacts(anyLong(), any())).thenReturn(WeeklyFacts.empty());

        ReportInputs in = provider.collect(9L, LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));

        assertThat(in.rosterSize()).isZero();
        assertThat(in.masteryRows()).isEmpty();
        assertThat(in.thisWeek().answers()).isZero();
        verify(analytics, never()).windowEngagement(anyLong(), any(), any());
    }
}
