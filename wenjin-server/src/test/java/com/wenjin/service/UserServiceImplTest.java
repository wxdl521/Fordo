package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.dto.LoginRequest;
import com.wenjin.dto.LoginVO;
import com.wenjin.dto.RegisterRequest;
import com.wenjin.dto.UserVO;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.TokenService;
import com.wenjin.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private SysUserMapper userMapper;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final TokenService tokenService = new TokenService("test-secret", 3600);
    private UserServiceImpl service() {
        return new UserServiceImpl(userMapper, encoder, tokenService);
    }

    @Test
    void register_hashesPassword() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(userMapper.insert(any(SysUser.class))).thenAnswer(inv -> {
            ((SysUser) inv.getArgument(0)).setId(10L);
            return 1;
        });

        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser");
        req.setPassword("pass123");
        req.setRealName("新同学");
        req.setRole(2);

        UserVO vo = service().register(req);

        assertThat(vo.getUsername()).isEqualTo("newuser");
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        String stored = captor.getValue().getPassword();
        assertThat(stored).startsWith("$2");           // 落库是 bcrypt 哈希
        assertThat(encoder.matches("pass123", stored)).isTrue();
    }

    @Test
    void register_duplicateUsername_throws() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        RegisterRequest req = new RegisterRequest();
        req.setUsername("demo_student");
        req.setPassword("pass");
        req.setRealName("test");
        req.setRole(2);
        assertThatThrownBy(() -> service().register(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名已存在");
    }

    @Test
    void login_bcrypt_success_returnsToken() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("demo_student");
        user.setPassword(encoder.encode("demo"));   // 已是哈希
        user.setRealName("林晚舟");
        user.setRole(2);
        user.setStatus(1);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);

        LoginRequest req = new LoginRequest();
        req.setUsername("demo_student");
        req.setPassword("demo");

        LoginVO vo = service().login(req);

        assertThat(vo.getUser().getId()).isEqualTo(1L);
        assertThat(vo.getUser().getRealName()).isEqualTo("林晚舟");
        assertThat(vo.getToken()).isNotBlank();
        assertThat(tokenService.verify(vo.getToken())).isPresent();
        assertThat(tokenService.verify(vo.getToken()).get().uid()).isEqualTo(1L);
        verify(userMapper, never()).updateById(any(SysUser.class)); // 已是哈希，不升级
    }

    @Test
    void login_legacyPlaintext_upgradesHashOnSuccess() {
        SysUser user = new SysUser();
        user.setId(3L);
        user.setUsername("demo_student");
        user.setPassword("demo");   // 遗留明文
        user.setRole(2);
        user.setStatus(1);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);

        LoginRequest req = new LoginRequest();
        req.setUsername("demo_student");
        req.setPassword("demo");

        LoginVO vo = service().login(req);

        assertThat(vo.getToken()).isNotBlank();
        ArgumentCaptor<SysUser> cap = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper, times(1)).updateById(cap.capture());
        assertThat(cap.getValue().getPassword()).startsWith("$2"); // 升级为哈希落库
    }

    @Test
    void login_wrongPassword_throws() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("demo_student");
        user.setPassword(encoder.encode("demo"));
        user.setRole(2);
        user.setStatus(1);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);

        LoginRequest req = new LoginRequest();
        req.setUsername("demo_student");
        req.setPassword("wrong");

        assertThatThrownBy(() -> service().login(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    void login_unknownUser_throws() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        LoginRequest req = new LoginRequest();
        req.setUsername("nope");
        req.setPassword("x");
        assertThatThrownBy(() -> service().login(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    void login_disabledUser_throws() {
        SysUser user = new SysUser();
        user.setId(3L);
        user.setUsername("disabled");
        user.setPassword(encoder.encode("pass"));
        user.setRole(2);
        user.setStatus(0);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);

        LoginRequest req = new LoginRequest();
        req.setUsername("disabled");
        req.setPassword("pass");

        assertThatThrownBy(() -> service().login(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号已被禁用");
    }

    @Test
    void getUserById_success() {
        SysUser user = new SysUser();
        user.setId(2L);
        user.setUsername("demo_teacher");
        user.setPassword(encoder.encode("demo"));
        user.setRealName("王老师");
        user.setRole(1);
        user.setStatus(1);
        when(userMapper.selectById(2L)).thenReturn(user);

        UserVO vo = service().getUserById(2L);

        assertThat(vo.getUsername()).isEqualTo("demo_teacher");
        assertThat(vo.getRole()).isEqualTo(1);
    }

    @Test
    void getUserById_notFound_throws() {
        when(userMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service().getUserById(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户不存在");
    }
}
