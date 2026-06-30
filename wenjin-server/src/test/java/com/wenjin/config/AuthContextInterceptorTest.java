package com.wenjin.config;

import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * AuthContextInterceptor 直测（Mockito + MockHttpServletRequest，无 @SpringBootTest）：
 * 合法头 → CurrentUser 已设；无头/非数字/未知用户 → 不设但仍 return true；
 * afterCompletion → CurrentUser 已清；OPTIONS 预检 → 跳过数据库查询。
 *
 * <p>注：SysUserMapper 用 Mockito mock，与 TeacherAuthInterceptorTest 保持一致风格。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthContextInterceptorTest {

    @Mock
    private SysUserMapper userMapper;

    private final MockHttpServletResponse resp = new MockHttpServletResponse();

    @AfterEach
    void clearCurrentUser() {
        CurrentUser.clear();
    }

    private AuthContextInterceptor interceptor() {
        return new AuthContextInterceptor(userMapper);
    }

    private SysUser userWithId(long id) {
        SysUser u = new SysUser();
        u.setId(id);
        return u;
    }

    @Test
    void validHeader_existingUser_setsCurrentUser() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        req.addHeader("X-User-Id", "2");
        when(userMapper.selectById(2L)).thenReturn(userWithId(2L));

        boolean result = interceptor().preHandle(req, resp, new Object());

        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isEqualTo(2L);
    }

    @Test
    void noHeader_doesNotSetCurrentUser_stillReturnsTrue() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        // 不设 X-User-Id

        boolean result = interceptor().preHandle(req, resp, new Object());

        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void nonNumericHeader_doesNotSetCurrentUser_stillReturnsTrue() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        req.addHeader("X-User-Id", "abc");

        boolean result = interceptor().preHandle(req, resp, new Object());

        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void unknownUser_doesNotSetCurrentUser_stillReturnsTrue() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        req.addHeader("X-User-Id", "999");
        when(userMapper.selectById(999L)).thenReturn(null); // 用户不存在

        boolean result = interceptor().preHandle(req, resp, new Object());

        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void afterCompletion_clearsCurrentUser() throws Exception {
        CurrentUser.set(2L); // 模拟 preHandle 已设置
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");

        interceptor().afterCompletion(req, resp, new Object(), null);

        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void optionsPreflight_passesWithoutSettingCurrentUser() throws Exception {
        // OPTIONS 预检不查库，不设 CurrentUser，直接放行
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/companion/chat");

        boolean result = interceptor().preHandle(req, resp, new Object());

        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isNull();
    }
}
