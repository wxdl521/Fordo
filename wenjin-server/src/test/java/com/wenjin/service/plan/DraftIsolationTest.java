package com.wenjin.service.plan;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.DailyBriefVO;
import com.wenjin.dto.PlanTaskVO;
import com.wenjin.dto.PlanVO;
import com.wenjin.dto.ReviewTodayVO;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.ReviewService;
import com.wenjin.support.plan.PlanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DRAFT 红线：不进 daily-brief、不被 re-plan 触碰。
 */
@ExtendWith(MockitoExtension.class)
class DraftIsolationTest {

    @Mock PlanService planService;
    @Mock ReviewService reviewService;
    @Mock StudentMasteryMapper masteryMapper;
    @Mock KgNodeMapper nodeMapper;
    @Mock MasteryDecayCalculator decayCalculator;
    @Mock PlanServiceImpl planServiceImpl;

    @Test
    @DisplayName("isDraftIsolated：DRAFT=true，ACTIVE=false")
    void draftFlag() {
        assertThat(PlanServiceImpl.isDraftIsolated(PlanStatus.DRAFT)).isTrue();
        assertThat(PlanServiceImpl.isDraftIsolated(PlanStatus.ACTIVE)).isFalse();
        assertThat(PlanServiceImpl.isDraftIsolated(PlanStatus.CANCELLED)).isFalse();
    }

    @Test
    @DisplayName("daily-brief：仅 ACTIVE 的今日任务；DRAFT 计划段为空")
    void brief_ignoresDraft() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setPlanEnabled(true);
        LimitsProperties limits = new LimitsProperties();

        ReviewTodayVO review = new ReviewTodayVO();
        review.setTotalDecayingCount(0);
        review.setItems(List.of());
        when(reviewService.getToday(1L, 2L)).thenReturn(review);

        PlanVO draft = new PlanVO();
        draft.setId(99L);
        draft.setStatus(PlanStatus.DRAFT);
        PlanTaskVO t = new PlanTaskVO();
        t.setNodeCode("X");
        draft.setTodayTasks(List.of(t));
        when(planService.current(1L, 2L)).thenReturn(draft);
        when(masteryMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(nodeMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        DailyBriefService brief = new DailyBriefService(
                planService, reviewService, masteryMapper, nodeMapper,
                decayCalculator, flags, limits);
        DailyBriefVO vo = brief.build(1L, 2L);

        assertThat(vo.getTodayPlanTasks()).isEmpty();
        assertThat(vo.isPlanSectionVisible()).isTrue();
        assertThat(vo.isAtRisk()).isFalse();
    }

    @Test
    @DisplayName("daily-brief：ACTIVE 今日任务进入聚合")
    void brief_includesActive() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setPlanEnabled(true);
        LimitsProperties limits = new LimitsProperties();

        ReviewTodayVO review = new ReviewTodayVO();
        review.setTotalDecayingCount(6);
        review.setItems(List.of());
        when(reviewService.getToday(1L, 2L)).thenReturn(review);

        PlanVO active = new PlanVO();
        active.setId(1L);
        active.setStatus(PlanStatus.ACTIVE);
        PlanTaskVO t = new PlanTaskVO();
        t.setNodeCode("A1");
        active.setTodayTasks(List.of(t));
        active.setAtRisk(false);
        when(planService.current(1L, 2L)).thenReturn(active);
        when(masteryMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(nodeMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        DailyBriefService brief = new DailyBriefService(
                planService, reviewService, masteryMapper, nodeMapper,
                decayCalculator, flags, limits);
        DailyBriefVO vo = brief.build(1L, 2L);

        assertThat(vo.getTodayPlanTasks()).hasSize(1);
        assertThat(vo.getTodayPlanTasks().get(0).getNodeCode()).isEqualTo("A1");
        assertThat(vo.isBacklogAlert()).isTrue();
    }

    @Test
    @DisplayName("replanAllActive：listActivePlans 不含 DRAFT 时 process 不触碰 DRAFT")
    void replan_onlyActive() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setPlanEnabled(true);
        when(planServiceImpl.listActivePlans()).thenReturn(List.of());

        PlanReplanScheduler scheduler = new PlanReplanScheduler(
                planServiceImpl, flags, java.time.Clock.systemUTC());
        int n = scheduler.replanAllActive();
        assertThat(n).isZero();
        verify(planServiceImpl, never()).replanOne(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("plan-enabled=false：scheduler 直接跳过")
    void replan_flagOff() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setPlanEnabled(false);
        PlanReplanScheduler scheduler = new PlanReplanScheduler(
                planServiceImpl, flags, java.time.Clock.systemUTC());
        assertThat(scheduler.replanAllActive()).isZero();
        verify(planServiceImpl, never()).listActivePlans();
    }
}
