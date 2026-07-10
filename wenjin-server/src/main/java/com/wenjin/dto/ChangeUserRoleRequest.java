package com.wenjin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 系统管理员变更用户角色请求。
 * <p>目标角色仅允许教师（1）或学生（2）。</p>
 */
@Data
public class ChangeUserRoleRequest {

    /** 目标角色：1=教师, 2=学生 */
    @NotNull(message = "目标角色不能为空")
    private Integer role;
}
