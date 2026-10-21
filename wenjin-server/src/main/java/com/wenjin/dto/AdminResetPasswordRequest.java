package com.wenjin.dto;

import lombok.Data;

/**
 * 管理员重置密码请求。{@code newPassword} 为空（null 或空串）时由服务端生成随机密码；
 * 非空时的长度校验（8-72）在服务层完成，以便把空串视为"未提供"。
 */
@Data
public class AdminResetPasswordRequest {

    private String newPassword;
}
