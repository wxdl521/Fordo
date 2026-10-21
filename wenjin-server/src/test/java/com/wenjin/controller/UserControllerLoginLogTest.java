package com.wenjin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.TokenService;
import com.wenjin.service.LoginLogService;
import com.wenjin.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/login 的登录日志：成功 / 密码错 / 用户不存在 / 账号禁用 各写一条；
 * 失败时响应与加日志前完全一致（401 + LOGIN_FAIL），日志里没有密码。
 */
@ExtendWith(MockitoExtension.class)
class UserControllerLoginLogTest {

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private LoginLogService loginLogService;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        UserServiceImpl userService = new UserServiceImpl(userMapper, encoder, new TokenService("test-secret", 3600));
        mvc = MockMvcBuilders.standaloneSetup(new UserController(userService, loginLogService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private SysUser user(long id, String username, String rawPassword, int status) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(username);
        u.setPassword(encoder.encode(rawPassword));
        u.setRealName("某人");
        u.setRole(2);
        u.setStatus(status);
        return u;
    }

    private static MockHttpServletRequestBuilder loginRequest(String username, String password) {
        return post("/api/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "JUnit-UA/1.0")
                .with(req -> {
                    req.setRemoteAddr("10.1.2.3");
                    return req;
                })
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    @Test
    @DisplayName("登录成功：写一条 success，带 userId / IP / UA，无失败原因")
    void login_success_recordsSuccess() throws Exception {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user(7L, "alice", "secret1", 1));

        mvc.perform(loginRequest("alice", "secret1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").isNotEmpty());

        verify(loginLogService).record(eq(7L), eq("alice"), eq(true), isNull(), eq("10.1.2.3"), eq("JUnit-UA/1.0"));
    }

    @Test
    @DisplayName("密码错：写一条 BAD_CREDENTIALS（带已定位的 userId），响应仍是 401 + 1004")
    void login_wrongPassword_recordsBadCredentials() throws Exception {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user(8L, "alice", "secret1", 1));

        mvc.perform(loginRequest("alice", "wrong-pass"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1004))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));

        verify(loginLogService).record(eq(8L), eq("alice"), eq(false), eq("BAD_CREDENTIALS"),
                eq("10.1.2.3"), eq("JUnit-UA/1.0"));
    }

    @Test
    @DisplayName("用户名不存在：写一条 BAD_CREDENTIALS，user_id 为空")
    void login_unknownUser_recordsBadCredentialsWithoutUserId() throws Exception {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        mvc.perform(loginRequest("ghost", "whatever"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1004));

        verify(loginLogService).record(isNull(), eq("ghost"), eq(false), eq("BAD_CREDENTIALS"),
                eq("10.1.2.3"), eq("JUnit-UA/1.0"));
    }

    @Test
    @DisplayName("账号禁用（密码正确）：写一条 DISABLED，响应文案仍是原来的“账号已被禁用”")
    void login_disabledUser_recordsDisabled() throws Exception {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user(9L, "bob", "secret1", 0));

        mvc.perform(loginRequest("bob", "secret1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1004))
                .andExpect(jsonPath("$.message").value("账号已被禁用"));

        verify(loginLogService).record(eq(9L), eq("bob"), eq(false), eq("DISABLED"),
                eq("10.1.2.3"), eq("JUnit-UA/1.0"));
    }

    @Test
    @DisplayName("IP 优先取 X-Forwarded-For 首段")
    void login_usesForwardedForIp() throws Exception {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user(7L, "alice", "secret1", 1));

        mvc.perform(loginRequest("alice", "secret1").header("X-Forwarded-For", "203.0.113.9, 10.0.0.1"))
                .andExpect(status().isOk());

        verify(loginLogService).record(eq(7L), eq("alice"), eq(true), isNull(), eq("203.0.113.9"),
                eq("JUnit-UA/1.0"));
    }

    @Test
    @DisplayName("请求体校验失败（用户名为空）不算一次登录尝试：不写日志")
    void login_invalidBody_notRecorded() throws Exception {
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(loginLogService);
    }
}
