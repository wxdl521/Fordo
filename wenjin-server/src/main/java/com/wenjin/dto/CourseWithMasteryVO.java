package com.wenjin.dto;

import lombok.Data;

/**
 * 学生已选课程 + 掌握度统计 VO（GET /api/course/my 响应体）。
 */
@Data
public class CourseWithMasteryVO {

    private Long courseId;
    private String code;
    private String name;
    private String description;

    /** 已掌握节点数（mastery_level=2） */
    private long masteredCount;
    /** 薄弱节点数（mastery_level=1） */
    private long weakCount;
    /** 未学节点数（mastery_level=0） */
    private long unlearnedCount;
}
