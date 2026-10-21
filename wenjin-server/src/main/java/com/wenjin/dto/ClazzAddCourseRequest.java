package com.wenjin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 班级关联课程。 */
@Data
public class ClazzAddCourseRequest {

    @NotNull(message = "courseId 不能为空")
    @Positive(message = "courseId 无效")
    private Long courseId;
}
