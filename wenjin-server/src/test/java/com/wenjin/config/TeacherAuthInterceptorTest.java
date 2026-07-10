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
 * 教师端鉴权：身份取自 CurrentUser 可信 Principal。
 * 缺身份→401，学生→403，教师/管理员放行，OPTIONS 跳过。
 */
class TeacherAuthInterceptorTest {

    private final TeacherAuthInterceptor interceptor = new TeacherAuthInterceptor();
    private final MockHttpServletResponse resp = new MockHttpServletResponse();

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    @Test
    void noCurrentUser_throwsUnauthorized() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/teacher/graph");
        assertThatThrownBy(() -> interceptor.preHandle(req, resp, new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("登录")
                .extracting("code").isEqualTo(401);
    }

    @Test
    void student_throwsForbidden() {
        CurrentUser.set(new CurrentPrincipal(10L, UserRole.STUDENT));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/teacher/graph/nodes");
        assertThatThrownBy(() -> interceptor.preHandle(req, resp, new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("教师")
                .extracting("code").isEqualTo(403);
    }

    @Test
    void teacher_passes() {
        CurrentUser.set(new CurrentPrincipal(2L, UserRole.TEACHER));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/teacher/graph");
        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
    }

    @Test
    void admin_passes() {
        CurrentUser.set(new CurrentPrincipal(3L, UserRole.ADMIN));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/teacher/graph");
        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
    }

    @Test
    void optionsPreflight_passes() {
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/teacher/graph");
        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
    }
}
