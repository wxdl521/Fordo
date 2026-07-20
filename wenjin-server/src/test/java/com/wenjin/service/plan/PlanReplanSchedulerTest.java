package com.wenjin.service.plan;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.entity.AgentPlan;
import com.wenjin.support.plan.PlanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanReplanSchedulerTest {

    @Mock PlanServiceImpl planService;

    @Test
    @DisplayName("Clock 可注入：replan 使用 clock 的 today")
    void clockInjected() {
        Instant t0 = Instant.parse("2026-07-10T00:00:00Z");
        MovableClock clock = new MovableClock(t0);
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setPlanEnabled(true);

        AgentPlan p = new AgentPlan();
        p.setId(1L);
        p.setStudentId(10L);
        p.setCourseId(20L);
        p.setStatus(PlanStatus.ACTIVE);
        when(planService.listActivePlans()).thenReturn(List.of(p));
        when(planService.replanOne(p)).thenReturn(false);

        PlanReplanScheduler scheduler = new PlanReplanScheduler(planService, flags, clock);
        assertThat(scheduler.replanAllActive()).isEqualTo(1);
        verify(planService).replanOne(p);

        clock.advance(Duration.ofDays(3));
        LocalDate later = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        assertThat(later).isEqualTo(LocalDate.of(2026, 7, 13));
    }

    @Test
    @DisplayName("单计划失败隔离：一个异常不阻断其余")
    void isolatedFailures() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setPlanEnabled(true);

        AgentPlan bad = new AgentPlan();
        bad.setId(1L);
        bad.setStudentId(1L);
        bad.setCourseId(1L);
        bad.setStatus(PlanStatus.ACTIVE);
        AgentPlan good = new AgentPlan();
        good.setId(2L);
        good.setStudentId(2L);
        good.setCourseId(1L);
        good.setStatus(PlanStatus.ACTIVE);

        when(planService.listActivePlans()).thenReturn(List.of(bad, good));
        doThrow(new RuntimeException("boom")).when(planService).replanOne(bad);
        when(planService.replanOne(good)).thenReturn(false);

        PlanReplanScheduler scheduler = new PlanReplanScheduler(
                planService, flags, Clock.systemUTC());
        // ok count only successful ones
        assertThat(scheduler.replanAllActive()).isEqualTo(1);
        verify(planService, times(1)).replanOne(bad);
        verify(planService, times(1)).replanOne(good);
    }

    @Test
    @DisplayName("listActive 返回多个 ACTIVE 全部处理")
    void processesAllActive() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setPlanEnabled(true);
        AgentPlan a = plan(1L);
        AgentPlan b = plan(2L);
        when(planService.listActivePlans()).thenReturn(List.of(a, b));
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            calls.incrementAndGet();
            return false;
        }).when(planService).replanOne(any());

        PlanReplanScheduler scheduler = new PlanReplanScheduler(
                planService, flags, Clock.systemUTC());
        assertThat(scheduler.replanAllActive()).isEqualTo(2);
        assertThat(calls.get()).isEqualTo(2);
    }

    private static AgentPlan plan(long id) {
        AgentPlan p = new AgentPlan();
        p.setId(id);
        p.setStudentId(id);
        p.setCourseId(1L);
        p.setStatus(PlanStatus.ACTIVE);
        return p;
    }

    static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.truncatedTo(ChronoUnit.MILLIS);
        }
    }
}
