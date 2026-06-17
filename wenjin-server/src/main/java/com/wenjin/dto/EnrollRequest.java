package com.wenjin.dto;

import lombok.Data;

/**
 * 选课请求体（POST /api/course/enroll）。
 */
@Data
public class EnrollRequest {

    private Long studentId;
    private Long courseId;
}
