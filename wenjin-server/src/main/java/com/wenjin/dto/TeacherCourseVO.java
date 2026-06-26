package com.wenjin.dto;

import lombok.Data;

/** 老师端课程下拉项。 */
@Data
public class TeacherCourseVO {
    private Long id;
    private String code;
    private String name;

    public TeacherCourseVO() {}

    public TeacherCourseVO(Long id, String code, String name) {
        this.id = id;
        this.code = code;
        this.name = name;
    }
}
