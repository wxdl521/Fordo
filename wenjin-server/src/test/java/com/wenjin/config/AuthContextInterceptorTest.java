package com.wenjin.config;

import com.wenjin.security.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthContextInterceptor 直测：合法 Bearer 令牌 → CurrentUser 已设；
 * 无头/伪造/过期 → 不设但仍放行；afterCompletion 清理；OPTIONS 跳过。
 */
class AuthContextInterceptorTest {

    private final TokenService tokenService = new TokenService("test-secret", 3600);
    private final MockHttpServletResponse resp = new MockHttpServletResponse();

    private AuthContextInterceptor interceptor() {
        return new AuthContextInterceptor(tokenService);
    }

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    @Test
    void validBearer_setsCurrentUser() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        req.addHeader("Authorization", "Bearer " + tokenService.issue(2L, 2));

        boolean result = interceptor().preHandle(req, resp, new Object());

        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isEqualTo(2L);
    }

    @Test
    void noHeader_anonymous_stillTrue() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        boolean result = interceptor().preHandle(req, resp, new Object());
        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void forgedToken_anonymous_stillTrue() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        req.addHeader("Authorization", "Bearer not.a.realtoken");
        boolean result = interceptor().preHandle(req, resp, new Object());
        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void expiredToken_anonymous() {
        TokenService past = new TokenService("test-secret", -10);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        req.addHeader("Authorization", "Bearer " + past.issue(2L, 2));
        interceptor().preHandle(req, resp, new Object());
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void afterCompletion_clears() {
        CurrentUser.set(2L);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        interceptor().afterCompletion(req, resp, new Object(), null);
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void optionsPreflight_skips() {
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/companion/chat");
        boolean result = interceptor().preHandle(req, resp, new Object());
        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isNull();
    }
}
