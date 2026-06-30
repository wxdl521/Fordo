package com.wenjin.config;

import com.wenjin.security.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 轻量认证上下文拦截器，挂载 {@code /api/**}。
 *
 * <p>从 {@code Authorization: Bearer <token>} 校验令牌（{@link TokenService}），
 * 通过则把令牌 uid 写入 {@link CurrentUser}；无头/伪造/过期一律保持匿名、不抛异常——
 * 是否拒绝交由各端点的 {@code AccessGuard.assertSelf} 按需决定。</p>
 *
 * <p>{@code afterCompletion} 清除 {@link CurrentUser}，防 ThreadLocal 泄漏到线程池复用的下一个请求。</p>
 */
@Component
public class AuthContextInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";

    private final TokenService tokenService;

    public AuthContextInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            tokenService.verify(header.substring(BEARER.length()).trim())
                    .ifPresent(claims -> CurrentUser.set(claims.uid()));
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
