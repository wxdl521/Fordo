package com.wenjin.service.plan;

import com.wenjin.dto.DraftPlanResultVO;
import com.wenjin.dto.PlanVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 学习计划服务（M10）：draft / confirm / cancel / progress。
 */
public interface PlanService {

    /**
     * 产出 DRAFT 计划 + 任务；旧 DRAFT 自动 CANCELLED。
     * 仅 DRAFT，不影响 daily-brief / re-plan。
     */
    DraftPlanResultVO draft(long studentId, long courseId, LocalDate targetDate,
                            List<String> scopeNodes, Long createdRunId);

    /** DRAFT→ACTIVE；非法迁移 409。 */
    PlanVO confirm(long studentId, long planId);

    /** DRAFT/ACTIVE→CANCELLED。 */
    PlanVO cancel(long studentId, long planId);

    /** 当前 DRAFT 或 ACTIVE（优先 ACTIVE）+ 今日任务 + 进度；无计划返回空态 VO。 */
    PlanVO current(long studentId, long courseId);

    /** 对话内进度（读档）；无计划明确空态。 */
    PlanVO progress(long studentId, long courseId);
}
