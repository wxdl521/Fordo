package com.wenjin.config;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 教师端 / 管理端鉴权拦截器。
 *
 * <p>前端在请求头 {@code X-User-Id} 中携带当前登录用户 id（见 web 端 http.js）。
 * 本拦截器据此回查数据库取真实角色：仅 {@code role == 1}（教师）放行，
 * 缺失身份 → 401，已登录但非教师 / 用户不存在 → 403/401。</p>
 *
 * <p>注意：身份仅靠请求头传递，{@code X-User-Id} 本身可被伪造；但角色以数据库为准，
 * 前端篡改 role 无法绕过。如需更强保证应引入 token/会话机制。</p>
 */
@Component
public class TeacherAuthInterceptor implements HandlerInterceptor {

    /** 教师角色值（与 SysUser.role 约定一致：1=teacher, 2=student）。 */
    private static final int ROLE_TEACHER = 1;

    static final String HEADER_USER_ID = "X-User-Id";

    private final SysUserMapper userMapper;

    public TeacherAuthInterceptor(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 预检请求直接放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String header = request.getHeader(HEADER_USER_ID);
        if (!StringUtils.hasText(header)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage());
        }

        long userId;
        try {
            userId = Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
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
