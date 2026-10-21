package com.wenjin.dto;

import lombok.Data;

/**
 * 登录日志视图（管理后台「登录日志」）。
 */
@Data
public class LoginLogVO {

    private Long id;
    /** 用户名不存在时为 null */
    private Long userId;
    private String username;
    private boolean success;
    /** BAD_CREDENTIALS / DISABLED；成功时为 null */
    private String failReason;
    private String ip;
    private String userAgent;
    /** ISO-8601 本地时间字符串（如 2026-10-21T10:15:30） */
    private String createdAt;
}
