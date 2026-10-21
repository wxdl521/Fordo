package com.wenjin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员修改用户资料请求（目前仅真实姓名）。
 */
@Data
public class AdminUpdateUserRequest {

    @NotBlank(message = "姓名不能为空")
    @Size(max = 64, message = "真实姓名长度不能超过 64")
    private String realName;
}
