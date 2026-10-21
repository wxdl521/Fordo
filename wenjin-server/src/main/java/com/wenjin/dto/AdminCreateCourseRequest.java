package com.wenjin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 管理员为指定教师新建课程。teacherId 必须是启用中的教师（服务层校验）。 */
@Data
public class AdminCreateCourseRequest {

    @NotBlank(message = "课程名称不能为空")
    @Size(max = 128, message = "课程名称长度不能超过 128")
    private String name;

    @NotNull(message = "teacherId 不能为空")
    @Positive(message = "teacherId 无效")
    private Long teacherId;
}
