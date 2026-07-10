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
 * 系统管理员路由鉴权：仅 {@code ADMIN} 可访问 {@code /api/system-admin/**}。
 * 身份取自 {@link CurrentUser}（数据库回查后的可信主体）。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        CurrentPrincipal principal = CurrentUser.getPrincipal();
        if (principal == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage());
        }
        if (!principal.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无访问权限：仅系统管理员可访问该功能");
        }
        return true;
    }
}
