package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求体。
 * <p>公开注册仅接受用户名/密码/真实姓名。遗留 JSON 字段 {@code role} 在兼容窗口内
 * 仅可反序列化到 {@link #ignoredRole}，不得作为业务输入（服务端强制学生角色）。
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 64, message = "用户名长度须在 3-64 之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 72, message = "密码长度须在 8-72 之间")
    private String password;

    /** 真实姓名可选；空串允许，最长 64。 */
    @Size(max = 64, message = "真实姓名长度不能超过 64")
    private String realName;

    /**
     * 兼容旧客户端提交的 role 字段；WRITE_ONLY 且业务层忽略。
     * 公开注册始终写入学生角色，防止客户端自助提权。
     */
    @JsonProperty(value = "role", access = JsonProperty.Access.WRITE_ONLY)
    private Integer ignoredRole;
}
