package com.wenjin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 按课程清空学生学习数据：{@code confirmText} 必须等于课程名（服务层校验）。 */
@Data
public class AdminDataResetRequest {

    @NotNull(message = "courseId 不能为空")
    @Positive(message = "courseId 无效")
    private Long courseId;

    @NotBlank(message = "请输入课程名确认")
    private String confirmText;
}
