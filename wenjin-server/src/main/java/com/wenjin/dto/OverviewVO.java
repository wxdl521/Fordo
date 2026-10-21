package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 平台总览（管理后台首页）。字段名即前端契约，改名需同步前端。
 *
 * <p>所有 {@code daily} 数组按日期升序、长度恒等于生效的 {@code days}，无数据的日期补 0。</p>
 */
@Data
public class OverviewVO {

    private Users users;
    private Courses courses;
    private Activity activity;
    private Ai ai;

    @Data
    public static class Users {
        private long total;
        private long teachers;
        private long students;
        private long admins;
        /** 已禁用（status=0）的用户数，不分角色 */
        private long disabled;
    }

    @Data
    public static class Courses {
        private long total;
        /** status=1 且未归档 */
        private long published;
        /** status=0 且未归档 */
        private long draft;
        /** archived_at 非空 */
        private long archived;
    }

    @Data
    public static class Activity {
        /** 今日有成功登录的去重用户数 */
        private long todayActiveUsers;
        /** 今日登录失败次数 */
        private long todayLoginFailures;
        private List<ActivityDay> daily;
    }

    @Data
    public static class ActivityDay {
        /** yyyy-MM-dd */
        private String date;
        /** 成功登录次数 */
        private long logins;
        /** 当日有成功登录的去重用户数 */
        private long activeUsers;
        /** 登录失败次数 */
        private long failures;
    }

    @Data
    public static class Ai {
        /** 学生侧 AI（agent_run）调用次数 */
        private long runs;
        /** status=2 的次数 */
        private long failedRuns;
        private long tokensIn;
        private long tokensOut;
        /** 有耗时记录的 run 的平均耗时（毫秒，四舍五入）；没有数据为 0 */
        private long avgDurationMs;
        private List<AiDay> daily;
        private List<AiKindStat> byKind;
        private List<AiModelStat> byModel;
        /** 教师侧异步 AI 作业（ai_job），无 token，只统计次数/状态/类型 */
        private Jobs jobs;
    }

    @Data
    public static class AiDay {
        /** yyyy-MM-dd */
        private String date;
        private long runs;
        /** tokens_in + tokens_out */
        private long tokens;
    }

    @Data
    public static class AiKindStat {
        private String kind;
        private long runs;
        private long tokens;
    }

    @Data
    public static class AiModelStat {
        /** 未记录模型的 run 归入 "unknown" */
        private String model;
        private long runs;
        private long tokens;
    }

    @Data
    public static class Jobs {
        private long total;
        /** status=2 */
        private long succeeded;
        /** status=3 */
        private long failed;
        private List<JobKindStat> byKind;
    }

    @Data
    public static class JobKindStat {
        private String kind;
        private long count;
    }
}
