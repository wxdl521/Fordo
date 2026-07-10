package com.wenjin.config;

import com.wenjin.common.BusinessException;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 系统管理员路由：仅 ADMIN 放行；教师/学生 → 403；匿名 → 401。
 */
class AdminAuthInterceptorTest {

    private final AdminAuthInterceptor interceptor = new AdminAuthInterceptor();
    private final MockHttpServletResponse resp = new MockHttpServletResponse();

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    @Test
    void noCurrentUser_throwsUnauthorized() {
        MockHttpServletRequest req = new MockHttpServletRequest("PATCH", "/api/system-admin/users/1/role");
        assertThatThrownBy(() -> interceptor.preHandle(req, resp, new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(401);
    }

    @Test
    void teacher_throwsForbidden() {
        CurrentUser.set(new CurrentPrincipal(2L, UserRole.TEACHER));
        MockHttpServletRequest req = new MockHttpServletRequest("PATCH", "/api/system-admin/users/1/role");
        assertThatThrownBy(() -> interceptor.preHandle(req, resp, new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("系统管理员")
                .extracting("code").isEqualTo(403);
    }

    @Test
    void student_throwsForbidden() {
        CurrentUser.set(new CurrentPrincipal(10L, UserRole.STUDENT));
        MockHttpServletRequest req = new MockHttpServletRequest("PATCH", "/api/system-admin/users/1/role");
        assertThatThrownBy(() -> interceptor.preHandle(req, resp, new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(403);
    }

    @Test
    void admin_passes() {
        CurrentUser.set(new CurrentPrincipal(1L, UserRole.ADMIN));
        MockHttpServletRequest req = new MockHttpServletRequest("PATCH", "/api/system-admin/users/5/role");
        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
    }

    @Test
    void optionsPreflight_passes() {
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/system-admin/users/1/role");
        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
    }
}
