package com.wenjin.dto;

import lombok.Data;

/** 老师端课程下拉项。published=true 表示已发布（学生可见）。 */
@Data
public class TeacherCourseVO {
    private Long id;
    private String code;
    private String name;
    private boolean published;

    public TeacherCourseVO() {}

    public TeacherCourseVO(Long id, String code, String name) {
        this(id, code, name, false);
    }

    public TeacherCourseVO(Long id, String code, String name, boolean published) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.published = published;
    }
}
