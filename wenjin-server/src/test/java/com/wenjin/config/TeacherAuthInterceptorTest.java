package com.wenjin.config;

import com.wenjin.common.BusinessException;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
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
 * 教师端鉴权：身份取自 CurrentUser（由 AuthContextInterceptor 验令牌后设置）。
 * 缺身份→401，非教师/未知→403/401，role==1 放行，OPTIONS 跳过。
 */
@ExtendWith(MockitoExtension.class)
class TeacherAuthInterceptorTest {

    @Mock
    private SysUserMapper userMapper;

    @InjectMocks
    private TeacherAuthInterceptor interceptor;

    private final MockHttpServletResponse resp = new MockHttpServletResponse();

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    private SysUser userWithRole(long id, int role) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setRole(role);
        u.setStatus(1);
        return u;
    }

    @Test
    void noCurrentUser_throwsUnauthorized() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/teacher/graph");
        assertThatThrownBy(() -> interceptor.preHandle(req, resp, new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("登录");
    }

    @Test
    void unknownUser_throwsUnauthorized() {
        CurrentUser.set(999L);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/teacher/graph");
        when(userMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> interceptor.preHandle(req, resp, new Object()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void student_throwsForbidden() {
        CurrentUser.set(10L);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/teacher/graph/nodes");
        when(userMapper.selectById(10L)).thenReturn(userWithRole(10L, 2));
        assertThatThrownBy(() -> interceptor.preHandle(req, resp, new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("教师");
    }

    @Test
    void teacher_passes() {
        CurrentUser.set(2L);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/teacher/graph");
        when(userMapper.selectById(2L)).thenReturn(userWithRole(2L, 1));
        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
    }

    @Test
    void optionsPreflight_passes() {
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/teacher/graph");
        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
    }
}
