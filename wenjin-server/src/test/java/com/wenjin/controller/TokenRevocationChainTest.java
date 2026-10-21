package com.wenjin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.config.AuthContextInterceptor;
import com.wenjin.config.CurrentUser;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.PrincipalLoader;
import com.wenjin.security.TokenService;
import com.wenjin.security.UserRole;
import com.wenjin.service.LoginLogService;
import com.wenjin.service.impl.AdminUserServiceImpl;
import com.wenjin.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重置密码后吊销旧令牌的全链路（不起 Spring 容器、不连库）：
 * 真实 TokenService + PrincipalLoader + AuthContextInterceptor + UserServiceImpl + AdminUserServiceImpl，
 * 用一个会"真的"合并补丁的内存 SysUserMapper 代替数据库。
 */
class TokenRevocationChainTest {

    private static final String SECRET = "chain-secret";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final TokenService tokenService = new TokenService(SECRET, 3600);

    private final Map<Long, SysUser> store = new HashMap<>();
    private AdminUserServiceImpl adminService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectById(anyLong())).thenAnswer(inv -> store.get(inv.<Long>getArgument(0)));
        // 登录只会用到 alice
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(inv -> store.get(1L));
        // 与 MyBatis-Plus updateById 一致：只合并非 null 字段
        when(userMapper.updateById(any(SysUser.class))).thenAnswer(inv -> {
            SysUser patch = inv.getArgument(0);
            SysUser target = store.get(patch.getId());
            if (patch.getPassword() != null) {
                target.setPassword(patch.getPassword());
            }
            if (patch.getTokenValidAfter() != null) {
                target.setTokenValidAfter(patch.getTokenValidAfter());
            }
            return 1;
        });

        store.put(1L, user(1L, "alice", "old-pass-1"));
        store.put(2L, user(2L, "bob", "bob-pass-1"));

        adminService = new AdminUserServiceImpl(userMapper, encoder, Clock.systemDefaultZone());

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new UserController(
                        new UserServiceImpl(userMapper, encoder, tokenService), mock(LoginLogService.class)))
                .addInterceptors(new AuthContextInterceptor(tokenService, new PrincipalLoader(userMapper)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    private SysUser user(long id, String username, String rawPassword) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(username);
        u.setPassword(encoder.encode(rawPassword));
        u.setRole(UserRole.STUDENT.code());
        u.setStatus(1);
        return u;
    }

    /** 手工签一枚令牌（TokenService.issue 总是用"现在"作 iat，造不出"重置之前签发"的令牌）。 */
    private static String signed(long uid, Long iatMs) {
        long exp = System.currentTimeMillis() / 1000 + 3600;
        String json = "{\"uid\":" + uid + ",\"role\":2,\"exp\":" + exp
                + (iatMs == null ? "" : ",\"iatMs\":" + iatMs) + "}";
        try {
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            String payload = b64.encodeToString(json.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return payload + "." + b64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int meStatus(String token) throws Exception {
        return mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("重置前：带 iat 的旧令牌和无 iat 的升级前令牌都能用")
    void beforeReset_oldTokensWork() throws Exception {
        long now = System.currentTimeMillis();

        assertThat(meStatus(signed(1L, now - 30_000))).isEqualTo(200);
        assertThat(meStatus(signed(1L, null))).isEqualTo(200);
    }

    @Test
    @DisplayName("重置密码后：旧令牌（含无 iat 的）立即 401；用新密码登录拿到的新令牌 200")
    void afterReset_oldTokensRejected_newLoginTokenWorks() throws Exception {
        long now = System.currentTimeMillis();
        String oldWithIat = signed(1L, now - 30_000);
        String legacyNoIat = signed(1L, null);
        assertThat(meStatus(oldWithIat)).isEqualTo(200);

        adminService.resetPassword(1L, "Brand-New-Pass9");

        assertThat(meStatus(oldWithIat)).isEqualTo(401);
        assertThat(meStatus(legacyNoIat)).isEqualTo(401);

        // 旧密码不再可用
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"old-pass-1\"}"))
                .andExpect(status().isUnauthorized());

        // 新密码登录 -> 新令牌（iatMs 不早于水位线）有效
        String body = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"Brand-New-Pass9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String newToken = com.jayway.jsonpath.JsonPath.read(body, "$.data.token");
        assertThat(meStatus(newToken)).isEqualTo(200);
    }

    @Test
    @DisplayName("登录后紧接着（同一秒内）被重置：刚签发的旧令牌也必须 401（毫秒精度，无同秒窗口）")
    void resetRightAfterLogin_sameSecondTokenRejected() throws Exception {
        String body = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"old-pass-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String justIssued = com.jayway.jsonpath.JsonPath.read(body, "$.data.token");
        assertThat(meStatus(justIssued)).isEqualTo(200);

        Thread.sleep(5); // 保证水位线严格晚于签发毫秒
        adminService.resetPassword(1L, "Brand-New-Pass9");

        assertThat(meStatus(justIssued)).isEqualTo(401);
    }

    @Test
    @DisplayName("只吊销被重置的那个用户：其他用户的令牌不受影响")
    void reset_doesNotAffectOtherUsers() throws Exception {
        long now = System.currentTimeMillis();
        String bobOld = signed(2L, now - 30_000);
        String bobLegacy = signed(2L, null);

        adminService.resetPassword(1L, "Brand-New-Pass9");

        assertThat(meStatus(bobOld)).isEqualTo(200);
        assertThat(meStatus(bobLegacy)).isEqualTo(200);
    }
}
