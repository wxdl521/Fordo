package com.wenjin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新增课程请求体。 */
@Data
public class CreateCourseRequest {

    @NotBlank(message = "课程名称不能为空")
    @Size(max = 128, message = "课程名称长度不能超过 128")
    private String name;
}
