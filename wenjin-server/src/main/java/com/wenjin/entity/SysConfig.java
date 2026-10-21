package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置覆盖层（sys_config 表）。只存白名单 key 的覆盖值；secret 类型 {@code configValue} 为 AES-GCM 密文。
 */
@Data
@TableName("sys_config")
public class SysConfig {

    /** 配置项 key（主键），如 {@code wenjin.ai.model} */
    @TableId(value = "config_key", type = IdType.INPUT)
    private String configKey;

    /** 覆盖值；secret 类型为密文(base64) */
    private String configValue;

    /** 1=密文, 0=明文 */
    private Integer encrypted;

    /** 最后修改人 sys_user.id（无外键） */
    private Long updatedBy;

    private LocalDateTime updatedAt;
}
