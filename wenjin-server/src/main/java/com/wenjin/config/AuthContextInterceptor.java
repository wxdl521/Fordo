package com.wenjin.config;

import com.wenjin.security.PrincipalLoader;
import com.wenjin.security.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * 轻量认证上下文拦截器，挂载 {@code /api/**}。
 *
 * <p>从 {@code Authorization: Bearer <token>} 校验令牌（{@link TokenService}），
 * 再经 {@link PrincipalLoader} 回查数据库：仅启用且角色可识别的用户写入
 * {@link CurrentUser}；无头/伪造/过期/禁用/不存在一律保持匿名、不抛异常——
 * 是否拒绝交由各端点的 {@code AccessGuard.assertSelf} 或角色拦截器按需决定。</p>
 *
 * <p>每次调度开始、异步处理交接和请求完成时清除 {@link CurrentUser}，
 * 防止 ThreadLocal 泄漏到线程池复用的下一个请求。</p>
 */
@Component
public class AuthContextInterceptor implements AsyncHandlerInterceptor {

    private static final String BEARER = "Bearer ";

    private final TokenService tokenService;
    private final PrincipalLoader principalLoader;

    public AuthContextInterceptor(TokenService tokenService, PrincipalLoader principalLoader) {
        this.tokenService = tokenService;
        this.principalLoader = principalLoader;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        CurrentUser.clear();
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            tokenService.verify(header.substring(BEARER.length()).trim())
                    .flatMap(claims -> principalLoader.load(claims.uid()))
                    .ifPresent(CurrentUser::set);
        }
        // 始终放行；鉴权拒绝由 AccessGuard / 角色拦截器在各端点按需触发
        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        CurrentUser.clear();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 无论请求成功或异常，都清理 ThreadLocal，防止线程池复用时数据污染
        CurrentUser.clear();
    }
}
