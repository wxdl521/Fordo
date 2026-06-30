package com.wenjin.dto;

import lombok.Data;

/** 登录返回：令牌 + 用户信息（不含密码）。 */
@Data
public class LoginVO {
    private String token;
    private UserVO user;
}
