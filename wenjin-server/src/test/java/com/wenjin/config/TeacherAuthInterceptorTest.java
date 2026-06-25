package com.wenjin.config;

import com.wenjin.common.BusinessException;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 教师端鉴权拦截器测试：仅 role==1（教师）可放行，
 * 缺身份 → 401，非教师/未知用户 → 403/401，OPTIONS 预检放行。
 */
@ExtendWith(MockitoExtension.class)
class TeacherAuthInterceptorTest {

    @Mock
    private SysUserMapper userMapper;

    @InjectMocks
    private TeacherAuthInterceptor interceptor;

    private final MockHttpServletResponse resp = new MockHttpServletResponse();

    private SysUser userWithRole(long id, int role) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setRole(role);
        u.setStatus(1);
        return u;
    }

    @Test
    void missingHeader_throwsUnauthorized() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/teacher/graph");

        assertThatThrownBy(() -> interceptor.preHandle(req, resp, new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("登录");
    }

    @Test
    void blankHeader_throwsUnauthorized() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/teacher/graph");
        req.addHeader("X-User-Id", "   ");

        assertThatThrownBy(() -> interceptor.preHandle(req, resp, new Object()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void nonNumericHeader_throwsUnauthorized() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/teacher/graph");
        req.addHeader("X-User-Id", "abc");

        assertThatThrownBy(() -> interceptor.preHandle(req, resp, new Object()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void unknownUser_throwsUnauthorized() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/teacher/graph");
        req.addHeader("X-User-Id", "999");
        when(userMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> interceptor.preHandle(req, resp, new Object()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void student_throwsForbidden() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/teacher/graph/nodes");
        req.addHeader("X-User-Id", "10");
        when(userMapper.selectById(10L)).thenReturn(userWithRole(10L, 2));

        assertThatThrownBy(() -> interceptor.preHandle(req, resp, new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("教师");
    }

    @Test
    void teacher_passes() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/teacher/graph");
        req.addHeader("X-User-Id", "2");
        when(userMapper.selectById(2L)).thenReturn(userWithRole(2L, 1));

        boolean result = interceptor.preHandle(req, resp, new Object());

        assertThat(result).isTrue();
    }

    @Test
    void optionsPreflight_passesWithoutAuth() throws Exception {
        // CORS 预检不应被鉴权拦截（不查库、直接放行）
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/teacher/graph");

        boolean result = interceptor.preHandle(req, resp, new Object());

        assertThat(result).isTrue();
    }
}
