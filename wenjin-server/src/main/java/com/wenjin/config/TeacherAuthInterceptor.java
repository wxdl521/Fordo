package com.wenjin.config;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.security.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 教师端 / 管理端鉴权拦截器。
 *
 * <p>身份取自 {@link CurrentUser}（由 {@link AuthContextInterceptor} 校验令牌并回查数据库后设置）。
 * 允许 {@code TEACHER} 或 {@code ADMIN}；缺身份 → 401，已登录但非教师/管理员 → 403。</p>
 *
 * <p>课程归属检查见后续任务，本拦截器只做角色边界。</p>
 */
@Component
public class TeacherAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        CurrentPrincipal principal = CurrentUser.getPrincipal();
        if (principal == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage());
        }
        if (!principal.isTeacher() && !principal.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无访问权限：仅教师可访问该功能");
        }
        return true;
    }
}
