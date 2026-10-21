package com.wenjin.config;

import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.PrincipalLoader;
import com.wenjin.security.TokenService;
import com.wenjin.security.UserRole;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 集成测试辅助：构造会把任意有效令牌 uid 映射为启用学生主体的 AuthContextInterceptor。
 */
public final class TestPrincipalSupport {

    private TestPrincipalSupport() {}

    /** 任意 uid 均视为启用学生（集成测试无需真实 DB）。 */
    public static PrincipalLoader acceptingStudentLoader() {
        PrincipalLoader loader = mock(PrincipalLoader.class);
        when(loader.load(anyLong())).thenAnswer(inv ->
                Optional.of(new CurrentPrincipal(inv.getArgument(0), UserRole.STUDENT)));
        // AuthContextInterceptor 走带令牌签发时间的重载
        when(loader.load(anyLong(), anyLong())).thenAnswer(inv ->
                Optional.of(new CurrentPrincipal(inv.getArgument(0), UserRole.STUDENT)));
        return loader;
    }

    public static AuthContextInterceptor authInterceptor(TokenService tokenService) {
        return new AuthContextInterceptor(tokenService, acceptingStudentLoader());
    }
}
