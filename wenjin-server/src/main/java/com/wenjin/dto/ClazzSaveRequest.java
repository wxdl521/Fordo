package com.wenjin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新建 / 修改班级。name 在服务层 trim 后落库；description 可空（空白视为 null）。 */
@Data
public class ClazzSaveRequest {

    @NotBlank(message = "班级名不能为空")
    @Size(max = 64, message = "班级名长度不能超过 64")
    private String name;

    @Size(max = 255, message = "班级说明长度不能超过 255")
    private String description;
}
