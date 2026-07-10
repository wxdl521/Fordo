package com.wenjin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.Result;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.ChangeUserRoleRequest;
import com.wenjin.dto.UserVO;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.UserRole;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统管理员用户管理（受 {@code AdminAuthInterceptor} 保护）。
 * <p>仅提供角色变更：目标角色只允许教师或学生。</p>
 */
@RestController
@RequestMapping("/api/system-admin")
public class SystemAdminUserController {

    private static final int STATUS_ACTIVE = 1;

    private final SysUserMapper userMapper;

    public SystemAdminUserController(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 变更用户角色。
     * PATCH /api/system-admin/users/{id}/role
     */
    @PatchMapping("/users/{id}/role")
    public Result<UserVO> changeRole(@PathVariable("id") Long id,
                                     @RequestBody ChangeUserRoleRequest request) {
        if (request == null || request.getRole() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "目标角色不能为空");
        }
        UserRole targetRole;
        try {
            targetRole = UserRole.fromCode(request.getRole());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无效的目标角色");
        }
        if (targetRole != UserRole.TEACHER && targetRole != UserRole.STUDENT) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "目标角色仅允许教师或学生");
        }

        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }

        // 禁止把禁用用户提升为教师
        if (targetRole == UserRole.TEACHER
                && (user.getStatus() == null || user.getStatus() != STATUS_ACTIVE)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "禁止将禁用用户提升为教师");
        }

        // 禁止修改最后一个启用管理员
        if (user.getRole() != null
                && user.getRole() == UserRole.ADMIN.code()
                && user.getStatus() != null
                && user.getStatus() == STATUS_ACTIVE) {
            Long activeAdmins = userMapper.selectCount(
                    new LambdaQueryWrapper<SysUser>()
                            .eq(SysUser::getRole, UserRole.ADMIN.code())
                            .eq(SysUser::getStatus, STATUS_ACTIVE));
            if (activeAdmins != null && activeAdmins <= 1) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "禁止修改最后一个启用管理员");
            }
        }

        user.setRole(targetRole.code());
        userMapper.updateById(user);

        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        return Result.ok(vo);
    }
}
