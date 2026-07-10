package com.wenjin.controller;

import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.config.CurrentUser;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.TokenService;
import com.wenjin.security.UserRole;
import com.wenjin.service.UserService;
import com.wenjin.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开注册鉴权边界 + 用户枚举移除 + /api/me 仅当前有效用户。
 */
@ExtendWith(MockitoExtension.class)
class UserControllerSecurityTest {

    @Mock
    private SysUserMapper userMapper;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final TokenService tokenService = new TokenService("test-secret", 3600);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        UserService userService = new UserServiceImpl(userMapper, encoder, tokenService);
        mvc = MockMvcBuilders.standaloneSetup(new UserController(userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("POST /api/register：JSON 传入 role=1 仍落库并返回学生角色")
    void register_jsonTeacherRole_isIgnoredAndStudentIsReturned() throws Exception {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(SysUser.class))).thenAnswer(inv -> {
            ((SysUser) inv.getArgument(0)).setId(42L);
            return 1;
        });

        mvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "attacker",
                                  "password": "safe-password-123",
                                  "realName": "提权尝试",
                                  "role": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.role").value(UserRole.STUDENT.code()))
                .andExpect(jsonPath("$.data.username").value("attacker"));

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.STUDENT.code());
    }

    @Test
    @DisplayName("POST /api/register：JSON 传入 role=3（管理员）仍返回学生角色")
    void register_jsonAdminRole_isIgnoredAndStudentIsReturned() throws Exception {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(SysUser.class))).thenAnswer(inv -> {
            ((SysUser) inv.getArgument(0)).setId(43L);
            return 1;
        });

        mvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "wannabe_admin",
                                  "password": "safe-password-123",
                                  "realName": "管理员尝试",
                                  "role": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.role").value(UserRole.STUDENT.code()));

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.STUDENT.code());
    }

    @Test
    @DisplayName("GET /api/user/{id} 已移除，返回 404")
    void publicUserById_isNotMapped() throws Exception {
        // 不挂 GlobalExceptionHandler，避免 NoHandlerFound 被兜底成 500
        UserService userService = new UserServiceImpl(userMapper, encoder, tokenService);
        MockMvc bare = MockMvcBuilders.standaloneSetup(new UserController(userService)).build();
        bare.perform(get("/api/user/2"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("匿名 GET /api/me → 401")
    void me_anonymous_returnsUnauthorized() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("GET /api/me 只返回当前有效用户")
    void me_returnsCurrentUserOnly() throws Exception {
        SysUser me = new SysUser();
        me.setId(7L);
        me.setUsername("alice");
        me.setRealName("爱丽丝");
        me.setRole(UserRole.STUDENT.code());
        me.setStatus(1);
        when(userMapper.selectById(7L)).thenReturn(me);

        CurrentUser.set(new CurrentPrincipal(7L, UserRole.STUDENT));

        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.username").value("alice"));
    }
}
