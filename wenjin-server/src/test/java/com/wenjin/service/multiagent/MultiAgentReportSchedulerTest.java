package com.wenjin.service.multiagent;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.AiJobVO;
import com.wenjin.entity.Course;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.service.AiJobService;
import com.wenjin.support.AiJobKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiAgentReportSchedulerTest {

    @Mock CourseMapper courseMapper;
    @Mock AiJobService aiJobService;

    private FeatureFlagsProperties flags;
    private MultiAgentReportScheduler scheduler;

    @BeforeEach
    void setUp() {
        flags = new FeatureFlagsProperties();
        // fixed: 2026-07-20 Monday → previous week 2026-07-13 .. 2026-07-19
        Clock clock = Clock.fixed(Instant.parse("2026-07-20T08:00:00Z"), ZoneId.of("UTC"));
        scheduler = new MultiAgentReportScheduler(flags, courseMapper, aiJobService, clock);
    }

    @Test
    @DisplayName("multi-agent-enabled=false → zero enqueue")
    void disabled_skips() {
        flags.setMultiAgentEnabled(false);
        assertThat(scheduler.enqueueWeeklyReports()).isZero();
        verify(courseMapper, never()).selectList(any());
        verify(aiJobService, never()).enqueueInternal(anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("enabled: enqueue weekly + supervisor per course with period key")
    void enabled_enqueuesBothKinds() {
        flags.setMultiAgentEnabled(true);
        Course c = new Course();
        c.setId(7L);
        c.setTeacherId(100L);
        when(courseMapper.selectList(any())).thenReturn(List.of(c));
        when(aiJobService.enqueueInternal(anyLong(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(new AiJobVO());

        int n = scheduler.enqueueWeeklyReports();
        assertThat(n).isEqualTo(2);

        ArgumentCaptor<String> kindCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(aiJobService, org.mockito.Mockito.times(2)).enqueueInternal(
                eq(7L), eq(100L), kindCap.capture(), keyCap.capture(), any());
        assertThat(kindCap.getAllValues()).containsExactlyInAnyOrder(
                AiJobKind.TEACHER_WEEKLY_REPORT, AiJobKind.SUPERVISOR_SAMPLE);
        assertThat(keyCap.getAllValues()).allMatch(k -> k.equals("course:7:period:2026-07-13"));
    }
}
