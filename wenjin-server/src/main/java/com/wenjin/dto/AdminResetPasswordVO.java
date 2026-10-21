package com.wenjin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 重置密码结果。仅当密码由服务端生成时 {@code password} 才有值（明文，只返回这一次）；
 * 管理员自行指定密码时为 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminResetPasswordVO {

    private String password;
}
