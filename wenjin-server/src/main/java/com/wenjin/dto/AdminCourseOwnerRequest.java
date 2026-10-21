package com.wenjin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 转让课程 owner。teacherId 必须是启用中的教师（服务层校验）。 */
@Data
public class AdminCourseOwnerRequest {

    @NotNull(message = "teacherId 不能为空")
    @Positive(message = "teacherId 无效")
    private Long teacherId;
}
