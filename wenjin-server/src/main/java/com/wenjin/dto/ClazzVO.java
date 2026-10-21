package com.wenjin.dto;

import lombok.Data;

/** 班级视图。人数 / 课程数由同一条 SQL 的 GROUP BY 子查询取齐。 */
@Data
public class ClazzVO {

    private Long id;
    private String name;
    private String description;
    private long studentCount;
    private long courseCount;
    /** 创建时间，ISO-8601 本地时间字符串（如 2026-10-21T10:15:30） */
    private String createdAt;
}
