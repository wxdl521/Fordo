package com.wenjin.dto;

import lombok.Data;

/** 班级关联的课程。 */
@Data
public class ClazzCourseVO {

    private Long id;
    private String name;
    /** 1=已发布, 0=草稿/下架 */
    private Integer status;
    private boolean archived;
}
