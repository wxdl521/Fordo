package com.wenjin.service;

import com.wenjin.dto.AdminCreateUserRequest;
import com.wenjin.dto.AdminResetPasswordVO;
import com.wenjin.dto.AdminUpdateUserRequest;
import com.wenjin.dto.AdminUserPageVO;
import com.wenjin.dto.AdminUserVO;
import com.wenjin.entity.SysUser;

import java.util.List;

/**
 * 管理后台用户管理（仅 ADMIN 可调用，由 {@code AdminAuthInterceptor} 在路由层保证）。
 */
public interface AdminUserService {

    /** 分页查询，按 id 倒序；keyword 对用户名/姓名模糊匹配。page/size 越界时被钳制（size 上限 100）。 */
    AdminUserPageVO list(String keyword, Integer role, Integer status, Integer page, Integer size);

    /** 创建教师或学生账号（BCrypt 存储）。 */
    AdminUserVO create(AdminCreateUserRequest request);

    /** 修改真实姓名。 */
    AdminUserVO updateRealName(Long id, AdminUpdateUserRequest request);

    /** 启用/禁用。不能禁用自己；不能禁用最后一个启用管理员。 */
    AdminUserVO changeStatus(Long id, Integer status);

    /**
     * 重置密码。{@code newPassword} 为空则服务端生成 12 位随机密码并在返回值中给出（仅此一次），
     * 否则使用指定密码且返回值 password 为 null。
     */
    AdminResetPasswordVO resetPassword(Long id, String newPassword);

    /** 单事务批量插入（调用方已完成校验与密码编码）；任一失败整体回滚。 */
    void insertAll(List<SysUser> users);
}
