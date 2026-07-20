package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 写类工具幂等键（tool_effect_key，M10）。
 * <p>唯一键 (tool_name, request_key)；key 约定 {@code run:{runId}:step:{seq}}。
 */
@Data
@TableName("tool_effect_key")
public class ToolEffectKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String toolName;

    private String requestKey;

    private LocalDateTime createdAt;
}
