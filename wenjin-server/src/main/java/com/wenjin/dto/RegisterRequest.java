package com.wenjin.dto;

import lombok.Data;

/**
 * 注册请求体。
 */
@Data
public class RegisterRequest {

    private String username;
    private String password;
    private String realName;
    /** 1=teacher, 2=student */
    private Integer role;
}
