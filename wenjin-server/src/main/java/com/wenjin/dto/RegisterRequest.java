package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 注册请求体。
 * <p>公开注册仅接受用户名/密码/真实姓名。遗留 JSON 字段 {@code role} 在兼容窗口内
 * 仅可反序列化到 {@link #ignoredRole}，不得作为业务输入（服务端强制学生角色）。
 */
@Data
public class RegisterRequest {

    private String username;
    private String password;
    private String realName;

    /**
     * 兼容旧客户端提交的 role 字段；WRITE_ONLY 且业务层忽略。
     * 公开注册始终写入学生角色，防止客户端自助提权。
     */
    @JsonProperty(value = "role", access = JsonProperty.Access.WRITE_ONLY)
    private Integer ignoredRole;
}
