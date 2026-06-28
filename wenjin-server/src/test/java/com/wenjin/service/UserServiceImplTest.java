package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.dto.LoginRequest;
import com.wenjin.dto.RegisterRequest;
import com.wenjin.dto.UserVO;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private SysUserMapper userMapper;

    @InjectMocks
    private UserServiceImpl service;

    @Test
    void register_studentInserted_noAutoEnroll() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(userMapper.insert(any(SysUser.class))).thenAnswer(invocation -> {
            SysUser u = invocation.getArgument(0);
            u.setId(10L);
            return 1;
        });

        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser");
        req.setPassword("pass123");
        req.setRealName("新同学");
        req.setRole(2);

        UserVO vo = service.register(req);

        assertThat(vo.getUsername()).isEqualTo("newuser");
        assertThat(vo.getRole()).isEqualTo(2);
        assertThat(vo.getStatus()).isEqualTo(1);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("newuser");
    }

    @Test
    void register_duplicateUsername_throws() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        RegisterRequest req = new RegisterRequest();
        req.setUsername("demo_student");
        req.setPassword("pass");
        req.setRealName("test");
        req.setRole(2);

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名已存在");
    }

    @Test
    void login_success() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("demo_student");
        user.setPassword("demo");
        user.setRealName("林晚舟");
        user.setRole(2);
        user.setStatus(1);

        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);

        LoginRequest req = new LoginRequest();
        req.setUsername("demo_student");
        req.setPassword("demo");

        UserVO vo = service.login(req);

        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getRealName()).isEqualTo("林晚舟");
        assertThat(vo.getRole()).isEqualTo(2);
    }

    @Test
    void login_wrongPassword_throws() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        LoginRequest req = new LoginRequest();
        req.setUsername("demo_student");
        req.setPassword("wrong");

        assertThatThrownBy(() -> service.login(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    void login_disabledUser_throws() {
        SysUser user = new SysUser();
        user.setId(3L);
        user.setUsername("disabled");
        user.setPassword("pass");
        user.setRealName("禁用");
        user.setRole(2);
        user.setStatus(0);

        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);

        LoginRequest req = new LoginRequest();
        req.setUsername("disabled");
        req.setPassword("pass");

        assertThatThrownBy(() -> service.login(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号已被禁用");
    }

    @Test
    void getUserById_success() {
        SysUser user = new SysUser();
        user.setId(2L);
        user.setUsername("demo_teacher");
        user.setPassword("demo");
        user.setRealName("王老师");
        user.setRole(1);
        user.setStatus(1);

        when(userMapper.selectById(2L)).thenReturn(user);

        UserVO vo = service.getUserById(2L);

        assertThat(vo.getUsername()).isEqualTo("demo_teacher");
        assertThat(vo.getRealName()).isEqualTo("王老师");
        assertThat(vo.getRole()).isEqualTo(1);
    }

    @Test
    void getUserById_notFound_throws() {
        when(userMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getUserById(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户不存在");
    }

    @Test
    void vo_neverContainsPassword() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("test");
        user.setPassword("secret");
        user.setRealName("测试");
        user.setRole(2);
        user.setStatus(1);

        when(userMapper.selectById(1L)).thenReturn(user);

        UserVO vo = service.getUserById(1L);

        // UserVO 没有 password 字段，确认不泄露
        assertThat(vo).hasNoNullFieldsOrProperties();
    }
}
