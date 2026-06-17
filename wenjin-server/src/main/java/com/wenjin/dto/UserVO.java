package com.wenjin.dto;

import lombok.Data;

/**
 * 用户信息 VO（不含密码），供接口返回。
 */
@Data
public class UserVO {

    private Long id;
    private String username;
    private String realName;
    /** 1=teacher, 2=student */
    private Integer role;
    /** 1=active, 0=disabled */
    private Integer status;
}
