package com.wenjin.dto;

import lombok.Data;

/**
 * 管理后台用户视图（不含密码）。
 */
@Data
public class AdminUserVO {

    private Long id;
    private String username;
    private String realName;
    /** 1=teacher, 2=student, 3=admin */
    private Integer role;
    /** 1=active, 0=disabled */
    private Integer status;
    /** 创建时间，ISO-8601 本地时间字符串（如 2026-10-21T10:15:30）；未知时为 null。 */
    private String createdAt;
}
