package com.wenjin.config;

import com.wenjin.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 轻量认证上下文拦截器，挂载 {@code /api/**}。
 *
 * <p>从请求头 {@code X-User-Id}（见 web 端 http.js）读取用户 ID，回查数据库确认用户存在后
 * 写入 {@link CurrentUser}；无头、解析失败、用户不存在时<strong>保持匿名不抛异常</strong>——
 * 是否拒绝由各端点的 {@link AccessGuard#assertSelf} 按业务需要决定。</p>
 *
 * <p>{@code afterCompletion} 必须清除 {@link CurrentUser}，防止 ThreadLocal 泄漏到
 * 线程池复用的下一个请求。</p>
 */
@Component
public class AuthContextInterceptor implements HandlerInterceptor {

    private final SysUserMapper userMapper;

    public AuthContextInterceptor(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 预检不查库，直接放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String header = request.getHeader(TeacherAuthInterceptor.HEADER_USER_ID);
        if (StringUtils.hasText(header)) {
            try {
                long id = Long.parseLong(header.trim());
                // 回查数据库，确认用户真实存在（否则保持匿名）
                if (userMapper.selectById(id) != null) {
                    CurrentUser.set(id);
                }
            } catch (NumberFormatException ignored) {
                // 非数字头，保持匿名——不抛，继续处理
            }
        }
        // 始终放行；鉴权拒绝由 AccessGuard.assertSelf 在各端点按需触发
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 无论请求成功或异常，都清理 ThreadLocal，防止线程池复用时数据污染
        CurrentUser.clear();
    }
}
