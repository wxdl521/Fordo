package com.wenjin.dto;

import lombok.Data;

/** 班级关联课程的结果：{@code enrolled} 为新补的 student_course 行数（已选过的跳过）。 */
@Data
public class ClazzCourseEnrollVO {

    private int enrolled;

    public ClazzCourseEnrollVO() {}

    public ClazzCourseEnrollVO(int enrolled) {
        this.enrolled = enrolled;
    }
}
