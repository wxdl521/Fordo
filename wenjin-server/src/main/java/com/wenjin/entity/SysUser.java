package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体（sys_user 表）。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String realName;

    /** 1=teacher, 2=student, 3=admin */
    private Integer role;

    /** 1=active, 0=disabled */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * 令牌吊销水位线：早于此刻签发的令牌一律无效（管理员重置密码时写入，秒级精度）。
     * NULL=从未吊销过。比较逻辑见 {@link com.wenjin.security.PrincipalLoader}。
     */
    private LocalDateTime tokenValidAfter;
}
