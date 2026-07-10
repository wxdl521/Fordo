package com.wenjin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 发布/下架课程请求体。 */
@Data
public class UpdateCourseStatusRequest {

    @NotNull(message = "published 不能为空")
    private Boolean published;
}
