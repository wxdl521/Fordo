package com.wenjin.dto;

import lombok.Data;

/**
 * 管理后台课程视图（含归档课程）。三个计数由同一条 SQL 的 GROUP BY 子查询一次取齐。
 */
@Data
public class AdminCourseVO {

    private Long id;
    /** 课程业务编码（如 52015CC4B4） */
    private String code;
    private String name;
    /** 任课教师（owner）的 sys_user.id */
    private Long teacherId;
    /** 任课教师显示名：优先真实姓名，缺省回落到用户名 */
    private String teacherName;
    /** 1=已发布, 0=草稿/下架（归档课程恒为 0） */
    private Integer status;
    private boolean archived;
    /** 归档时间，ISO-8601 本地时间字符串；未归档为 null */
    private String archivedAt;
    /** 图谱节点数（归档课程为随课程归档的节点数） */
    private long nodeCount;
    /** 已选课学生数（student_course 行数） */
    private long studentCount;
    /** 关联到的班级数 */
    private long clazzCount;
    /** 创建时间，ISO-8601 本地时间字符串（如 2026-10-21T10:15:30） */
    private String createdAt;
}
