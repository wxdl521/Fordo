package com.wenjin.config;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 教师端 / 管理端鉴权拦截器。
 *
 * <p>身份取自 {@link CurrentUser}（由 {@link AuthContextInterceptor} 校验 Bearer 令牌后设置，
 * 已不可伪造）。据此回查数据库取真实角色：仅 {@code role == 1}（教师）放行，
 * 缺身份 → 401，已登录但非教师 / 用户不存在 → 403/401。</p>
 */
@Component
public class TeacherAuthInterceptor implements HandlerInterceptor {

    /** 教师角色值（与 SysUser.role 约定一致：1=teacher, 2=student）。 */
    private static final int ROLE_TEACHER = 1;

    private final SysUserMapper userMapper;

    public TeacherAuthInterceptor(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        Long userId = CurrentUser.get();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage());
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage());
        }
        if (user.getRole() == null || user.getRole() != ROLE_TEACHER) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无访问权限：仅教师可访问该功能");
        }
        return true;
    }
}
