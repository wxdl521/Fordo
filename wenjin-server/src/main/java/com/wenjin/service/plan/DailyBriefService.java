package com.wenjin.service.plan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.DailyBriefVO;
import com.wenjin.dto.PlanTaskVO;
import com.wenjin.dto.PlanVO;
import com.wenjin.dto.ReviewTodayVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.ReviewService;
import com.wenjin.support.plan.PlanStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 每日简报：读时聚合（今日计划任务 + 复习积压 + 弱跌节点），无新表、无 scheduler。
 * <p><b>DRAFT 红线</b>：仅 ACTIVE 计划的今日任务进入 brief。
 */
@Service
public class DailyBriefService {

    private final PlanService planService;
    private final ReviewService reviewService;
    private final StudentMasteryMapper masteryMapper;
    private final KgNodeMapper nodeMapper;
    private final MasteryDecayCalculator decayCalculator;
    private final FeatureFlagsProperties featureFlags;
    private final LimitsProperties limits;

    public DailyBriefService(PlanService planService,
                             ReviewService reviewService,
                             StudentMasteryMapper masteryMapper,
                             KgNodeMapper nodeMapper,
                             MasteryDecayCalculator decayCalculator,
                             FeatureFlagsProperties featureFlags,
                             LimitsProperties limits) {
        this.planService = Objects.requireNonNull(planService, "planService");
        this.reviewService = Objects.requireNonNull(reviewService, "reviewService");
        this.masteryMapper = masteryMapper;
        this.nodeMapper = nodeMapper;
        this.decayCalculator = decayCalculator;
        this.featureFlags = featureFlags;
        this.limits = limits == null ? new LimitsProperties() : limits;
    }

    public DailyBriefVO build(long studentId, long courseId) {
        DailyBriefVO vo = new DailyBriefVO();
        boolean planOn = featureFlags == null || featureFlags.isPlanEnabled();
        vo.setPlanSectionVisible(planOn);

        // 复习积压（M3 队列全量计数）
        ReviewTodayVO review = reviewService.getToday(studentId, courseId);
        int backlog = review == null ? 0 : review.getTotalDecayingCount();
        vo.setReviewBacklogCount(backlog);
        int thr = limits.getPlan().getBacklogThreshold();
        vo.setBacklogAlert(backlog > thr);

        // 计划段：仅 ACTIVE
        List<PlanTaskVO> todayPlan = List.of();
        boolean atRisk = false;
        if (planOn) {
            PlanVO current = planService.current(studentId, courseId);
            if (current != null
                    && current.getStatus() != null
                    && current.getStatus() == PlanStatus.ACTIVE) {
                todayPlan = current.getTodayTasks() == null ? List.of() : current.getTodayTasks();
                atRisk = current.isAtRisk();
            }
            // DRAFT 故意忽略
        }
        vo.setTodayPlanTasks(todayPlan);
        vo.setAtRisk(atRisk);

        // 有效分跌破 weak 阈值的节点（最多 weakDropLimit）
        vo.setWeakDrops(loadWeakDrops(studentId, courseId));
        return vo;
    }

    private List<DailyBriefVO.WeakDropItem> loadWeakDrops(long studentId, long courseId) {
        double weakThr = limits.getPlan().getWeakThreshold();
        int limit = Math.max(0, limits.getPlan().getWeakDropLimit());
        if (limit == 0) {
            return List.of();
        }
        List<KgNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .isNull(KgNode::getArchivedAt));
        Map<Long, KgNode> byId = nodes.stream()
                .collect(Collectors.toMap(KgNode::getId, n -> n, (a, b) -> a));
        List<StudentMastery> rows = masteryMapper.selectList(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, studentId)
                .eq(StudentMastery::getCourseId, courseId));
        List<DailyBriefVO.WeakDropItem> drops = new ArrayList<>();
        for (StudentMastery m : rows) {
            if (m.getMasteryScore() == null) {
                continue;
            }
            double raw = m.getMasteryScore().doubleValue();
            // 「新跌破」：raw 曾高于 weak，effective 现低于 weak
            if (raw < weakThr) {
                continue;
            }
            double eff = decayCalculator.effectiveScoreOf(m);
            if (eff >= weakThr) {
                continue;
            }
            KgNode node = byId.get(m.getNodeId());
            if (node == null) {
                continue;
            }
            DailyBriefVO.WeakDropItem item = new DailyBriefVO.WeakDropItem();
            item.setNodeCode(node.getNodeCode());
            item.setName(node.getName());
            item.setEffectiveScore(eff);
            drops.add(item);
        }
        drops.sort(Comparator.comparingDouble(DailyBriefVO.WeakDropItem::getEffectiveScore));
        if (drops.size() > limit) {
            return List.copyOf(drops.subList(0, limit));
        }
        return List.copyOf(drops);
    }
}
