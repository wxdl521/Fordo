package com.wenjin.dto;

import lombok.Data;

/**
 * 平台总览聚合查询的 Mapper 投影行（{@code MonitorMapper} 的 GROUP BY / 汇总结果）。
 * 数值一律 {@link Long}：MySQL 的 SUM/COUNT 返回 DECIMAL/BIGINT，统一成 Long 省得到处转型。
 */
public final class MonitorRows {

    private MonitorRows() {}

    @Data
    public static class UserCounts {
        private Long total;
        private Long teachers;
        private Long students;
        private Long admins;
        private Long disabled;
    }

    @Data
    public static class CourseCounts {
        private Long total;
        private Long published;
        private Long draft;
        private Long archived;
    }

    @Data
    public static class LoginDay {
        /** yyyy-MM-dd */
        private String day;
        private Long logins;
        private Long activeUsers;
        private Long failures;
    }

    @Data
    public static class AiTotals {
        private Long runs;
        private Long failedRuns;
        private Long tokensIn;
        private Long tokensOut;
        private Long avgDurationMs;
    }

    @Data
    public static class AiDay {
        /** yyyy-MM-dd */
        private String day;
        private Long runs;
        private Long tokens;
    }

    @Data
    public static class NameStat {
        private String name;
        private Long runs;
        private Long tokens;
    }

    @Data
    public static class JobTotals {
        private Long total;
        private Long succeeded;
        private Long failed;
    }

    @Data
    public static class NameCount {
        private String name;
        private Long cnt;
    }
}
