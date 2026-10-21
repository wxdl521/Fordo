package com.wenjin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员创建用户请求。目标角色仅允许教师（1）或学生（2）；管理员不能经接口创建。
 * <p>用户名在服务层 trim 后再校验长度；密码不 trim。</p>
 */
@Data
public class AdminCreateUserRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 64, message = "用户名长度须在 3-64 之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 72, message = "密码长度须在 8-72 之间")
    private String password;

    /** 真实姓名可选；最长 64。 */
    @Size(max = 64, message = "真实姓名长度不能超过 64")
    private String realName;

    /** 1=教师, 2=学生 */
    @NotNull(message = "角色不能为空")
    private Integer role;
}
