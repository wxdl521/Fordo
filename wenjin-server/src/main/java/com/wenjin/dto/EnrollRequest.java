package com.wenjin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 选课请求体（POST /api/course/enroll）。
 * <p>学生身份一律从认证主体派生，请求体仅含课程 ID。</p>
 */
@Data
public class EnrollRequest {

    @NotNull
    @Positive
    private Long courseId;
}
