package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 学生每日简报（读时聚合，无新表）。
 */
@Data
public class DailyBriefVO {
    private List<PlanTaskVO> todayPlanTasks;
    private int reviewBacklogCount;
    private boolean backlogAlert;
    private List<WeakDropItem> weakDrops;
    private boolean planSectionVisible;
    private boolean atRisk;

    @Data
    public static class WeakDropItem {
        private String nodeCode;
        private String name;
        private double effectiveScore;
    }
}
