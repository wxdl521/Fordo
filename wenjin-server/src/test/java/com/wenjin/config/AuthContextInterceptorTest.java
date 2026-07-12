package com.wenjin.config;

import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.PrincipalLoader;
import com.wenjin.security.TokenService;
import com.wenjin.security.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * AuthContextInterceptor：合法且启用的用户 → 写入可信 Principal；
 * 禁用/不存在/无头/伪造/过期 → 保持匿名；afterCompletion 无条件清理。
 */
@ExtendWith(MockitoExtension.class)
class AuthContextInterceptorTest {

    @Mock
    private TokenService tokenService;

    @Mock
    private SysUserMapper userMapper;

    private PrincipalLoader principalLoader;
    private AuthContextInterceptor interceptor;
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void setUp() {
        principalLoader = new PrincipalLoader(userMapper);
        interceptor = new AuthContextInterceptor(tokenService, principalLoader);
    }

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    private static TokenService.Claims claims(long uid) {
        return new TokenService.Claims(uid, 2, System.currentTimeMillis() / 1000 + 3600);
    }

    private static SysUser user(long id, int role, int status) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setRole(role);
        u.setStatus(status);
        return u;
    }

    private static MockHttpServletRequest requestWithBearer(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        req.addHeader("Authorization", "Bearer " + token);
        return req;
    }

    @Test
    void validTokenForDisabledUser_remainsAnonymous() {
        when(tokenService.verify("token")).thenReturn(Optional.of(claims(7L)));
        when(userMapper.selectById(7L)).thenReturn(user(7L, 2, 0));
        interceptor.preHandle(requestWithBearer("token"), response, new Object());
        assertThat(CurrentUser.get()).isNull();
        assertThat(CurrentUser.getPrincipal()).isNull();
    }

    @Test
    void validTokenForMissingUser_remainsAnonymous() {
        when(tokenService.verify("token")).thenReturn(Optional.of(claims(99L)));
        when(userMapper.selectById(99L)).thenReturn(null);
        interceptor.preHandle(requestWithBearer("token"), response, new Object());
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void validTokenForActiveUser_setsPrincipalFromDbRole() {
        when(tokenService.verify("token")).thenReturn(Optional.of(claims(2L)));
        when(userMapper.selectById(2L)).thenReturn(user(2L, UserRole.TEACHER.code(), 1));
        boolean result = interceptor.preHandle(requestWithBearer("token"), response, new Object());
        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isEqualTo(2L);
        CurrentPrincipal p = CurrentUser.getPrincipal();
        assertThat(p).isNotNull();
        assertThat(p.role()).isEqualTo(UserRole.TEACHER);
        assertThat(p.isTeacher()).isTrue();
    }

    @Test
    void noHeader_anonymous_stillTrue() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        boolean result = interceptor.preHandle(req, response, new Object());
        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void forgedToken_anonymous_stillTrue() {
        when(tokenService.verify("not.a.realtoken")).thenReturn(Optional.empty());
        MockHttpServletRequest req = requestWithBearer("not.a.realtoken");
        boolean result = interceptor.preHandle(req, response, new Object());
        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void afterCompletion_clearsUnconditionally() {
        CurrentUser.set(new CurrentPrincipal(2L, UserRole.STUDENT));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        interceptor.afterCompletion(req, response, new Object(), null);
        assertThat(CurrentUser.get()).isNull();
        assertThat(CurrentUser.getPrincipal()).isNull();
    }

    @Test
    void preHandle_clearsPrincipalLeftByPreviousRequest() {
        CurrentUser.set(new CurrentPrincipal(99L, UserRole.STUDENT));
        interceptor.preHandle(new MockHttpServletRequest("GET", "/api/course/my"), response, new Object());
        assertThat(CurrentUser.getPrincipal()).isNull();
    }

    @Test
    void afterConcurrentHandlingStarted_clearsOriginalServletThread() {
        CurrentUser.set(new CurrentPrincipal(7L, UserRole.STUDENT));
        interceptor.afterConcurrentHandlingStarted(
                new MockHttpServletRequest("POST", "/api/companion/chat"), response, new Object());
        assertThat(CurrentUser.getPrincipal()).isNull();
    }

    @Test
    void optionsPreflight_clearsBeforeSkipping() {
        CurrentUser.set(new CurrentPrincipal(7L, UserRole.STUDENT));
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/companion/chat");
        boolean result = interceptor.preHandle(req, response, new Object());
        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isNull();
        assertThat(CurrentUser.getPrincipal()).isNull();
    }

    @Test
    void unknownRole_remainsAnonymous() {
        when(tokenService.verify("token")).thenReturn(Optional.of(claims(5L)));
        when(userMapper.selectById(5L)).thenReturn(user(5L, 99, 1));
        interceptor.preHandle(requestWithBearer("token"), response, new Object());
        assertThat(CurrentUser.get()).isNull();
    }
}
