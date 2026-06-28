package com.wenjin.dto;

import lombok.Data;

/** 发布/下架课程请求体。 */
@Data
public class UpdateCourseStatusRequest {
    private Boolean published;
}
