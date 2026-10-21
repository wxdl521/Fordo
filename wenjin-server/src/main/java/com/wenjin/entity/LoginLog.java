package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录日志（login_log 表），成功与失败都记；失败不记录密码。
 */
@Data
@TableName("login_log")
public class LoginLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户名不存在时为 null */
    private Long userId;

    /** 尝试登录的用户名（截断到 64） */
    private String username;

    /** 1=成功，0=失败 */
    private Integer success;

    /** BAD_CREDENTIALS / DISABLED；成功时为 null */
    private String failReason;

    private String ip;

    private String userAgent;

    /** 缺省由数据库 DEFAULT CURRENT_TIMESTAMP 生成 */
    private LocalDateTime createdAt;
}
