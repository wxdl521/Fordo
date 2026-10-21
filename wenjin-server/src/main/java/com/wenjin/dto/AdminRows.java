package com.wenjin.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台课程 / 班级列表的 Mapper 投影行（含 GROUP BY 计数）。
 * 数值一律 {@link Long}（COUNT 返回 BIGINT）；时间保持 {@link LocalDateTime}，由服务层格式化。
 */
public final class AdminRows {

    private AdminRows() {}

    @Data
    public static class CourseRow {
        private Long id;
        private String code;
        private String name;
        private Long teacherId;
        private String teacherUsername;
        private String teacherRealName;
        private Integer status;
        private LocalDateTime archivedAt;
        private LocalDateTime createdAt;
        private Long nodeCount;
        private Long studentCount;
        private Long clazzCount;
    }

    @Data
    public static class ClazzRow {
        private Long id;
        private String name;
        private String description;
        private LocalDateTime createdAt;
        private Long studentCount;
        private Long courseCount;
    }
}
