# 认证里程碑（令牌认证 + 密码哈希）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用服务端签发、客户端无法伪造的 HMAC 签名令牌替换"裸信 `X-User-Id`"的身份机制，并把明文密码改为 BCrypt 哈希（遗留明文登录时透明升级）。

**Architecture:** 无状态 A 档。登录校验密码后签发两段式令牌 `payloadB64.signatureB64`（HMAC-SHA256 over payload，payload=`{uid,role,exp}`）。`AuthContextInterceptor` 在 `/api/**` 验 `Authorization: Bearer` 令牌并填 `CurrentUser`；`TeacherAuthInterceptor` 改读 `CurrentUser`。现有授权层（`AccessGuard`、会话/写端点守卫、匿名看图模型）一律不动。

**Tech Stack:** Java 21 / Spring Boot 3 / MyBatis-Plus；JDK `javax.crypto.Mac`（HMAC，无新依赖）；`spring-security-crypto`（BCrypt，唯一新依赖）；Vue 3 + Vite（axios + 原生 fetch）。

设计依据：`docs/superpowers/specs/2026-06-30-authentication-design.md`。

## Global Constraints

- 后端**只允许新增一个依赖** `org.springframework.security:spring-security-crypto`（版本由 Spring Boot BOM 管理，pom 里不写 version）。不得引入其它库。
- `com.wenjin.security.TokenService` **不依赖其它 Spring bean**，可通过 `new TokenService(secret, ttl)` 直接构造做纯单测。
- 令牌格式固定：`payloadB64.signatureB64`；`payload` JSON = `{"uid":<long>,"role":<int>,"exp":<epochSeconds>}`，base64url **无填充**；`signature = HMAC-SHA256(secret, payloadB64)` base64url 无填充。
- 配置：`wenjin.auth.secret`（环境变量 `WENJIN_AUTH_SECRET` 覆盖，dev 默认值）、`wenjin.auth.ttl-seconds`（默认 `604800`）。
- **不改**现有授权层：`AccessGuard.assertSelf`、`CompanionServiceImpl.assertConversationOwner`、submit/generate/item-complete 守卫、"匿名放行、端点自决"模型、匿名看图谱（`studentId` 为 null）。只改 `CurrentUser` 的填充方式与登录/密码。
- 前端登录页路由路径是 `/`（401 跳转目标是 `/`，不是 `/login`）。
- `BusinessException` 经 `GlobalExceptionHandler` → HTTP 200 + body `code`（401/403）；`Result` 成功 `code=0`。
- 中文注释，沿用仓库风格。**每个任务一个 commit**；后端任务跑 `cd wenjin-server && ./mvnw -q test`，前端任务跑 `cd wenjin-web && npm run build`，全绿再提交。
- 工作分支：`feature/auth-token`（已创建，spec 已在其上）。

---

### Task 1: TokenService（签发/校验，纯单测）

**Files:**
- Create: `wenjin-server/src/main/java/com/wenjin/security/TokenService.java`
- Test: `wenjin-server/src/test/java/com/wenjin/security/TokenServiceTest.java`
- Modify: `wenjin-server/src/main/resources/application.yml`（加 `wenjin.auth` 段）

**Interfaces:**
- Produces:
  - `public record Claims(long uid, int role, long exp)`（嵌套于 TokenService）
  - `public TokenService(String secret, long ttlSeconds)`（另有 `@Value` 构造供 Spring）
  - `public String issue(long uid, int role)`
  - `public Optional<TokenService.Claims> verify(String token)`（无效/篡改/过期/格式错 → `Optional.empty()`）

- [ ] **Step 1: 写失败测试**

`wenjin-server/src/test/java/com/wenjin/security/TokenServiceTest.java`：
```java
package com.wenjin.security;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private final TokenService svc = new TokenService("test-secret-key", 3600);

    @Test
    void issueThenVerify_roundTrip() {
        String token = svc.issue(2L, 2);
        Optional<TokenService.Claims> c = svc.verify(token);
        assertThat(c).isPresent();
        assertThat(c.get().uid()).isEqualTo(2L);
        assertThat(c.get().role()).isEqualTo(2);
        assertThat(c.get().exp()).isGreaterThan(System.currentTimeMillis() / 1000);
    }

    @Test
    void tamperedPayload_rejected() {
        String token = svc.issue(2L, 2);
        // 篡改 payload 第一个字符
        String tampered = (token.charAt(0) == 'A' ? 'B' : 'A') + token.substring(1);
        assertThat(svc.verify(tampered)).isEmpty();
    }

    @Test
    void wrongSecret_rejected() {
        String token = svc.issue(2L, 2);
        TokenService other = new TokenService("another-secret", 3600);
        assertThat(other.verify(token)).isEmpty();
    }

    @Test
    void expired_rejected() {
        TokenService past = new TokenService("test-secret-key", -10); // exp 落在过去
        String token = past.issue(2L, 2);
        assertThat(past.verify(token)).isEmpty();
    }

    @Test
    void malformed_rejected() {
        assertThat(svc.verify(null)).isEmpty();
        assertThat(svc.verify("")).isEmpty();
        assertThat(svc.verify("nodothere")).isEmpty();
        assertThat(svc.verify("only.")).isEmpty();
        assertThat(svc.verify(".only")).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd wenjin-server && ./mvnw -q -Dtest=TokenServiceTest test`
Expected: 编译失败（`TokenService` 不存在）。

- [ ] **Step 3: 实现 TokenService**

`wenjin-server/src/main/java/com/wenjin/security/TokenService.java`：
```java
package com.wenjin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * 无状态令牌服务：签发/校验两段式 HMAC 签名令牌 {@code payloadB64.signatureB64}。
 *
 * <p>载荷 {@link Claims} 仅含 uid/role/exp，base64url 无填充编码后用 HMAC-SHA256 签名；
 * 校验时常量时间比对签名并查过期。无其它 Spring 依赖，可直接 {@code new} 出来做单测。</p>
 */
@Component
public class TokenService {

    /** 令牌载荷：用户 id、角色、过期时间（epoch 秒）。 */
    public record Claims(long uid, int role, long exp) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlSeconds;

    public TokenService(@Value("${wenjin.auth.secret:dev-secret-change-me-in-prod}") String secret,
                        @Value("${wenjin.auth.ttl-seconds:604800}") long ttlSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    /** 签发令牌：exp = now + ttl。 */
    public String issue(long uid, int role) {
        long exp = System.currentTimeMillis() / 1000 + ttlSeconds;
        String payloadJson;
        try {
            payloadJson = MAPPER.writeValueAsString(new Claims(uid, role, exp));
        } catch (Exception e) {
            throw new IllegalStateException("令牌载荷序列化失败", e);
        }
        String payloadB64 = B64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return payloadB64 + "." + sign(payloadB64);
    }

    /** 校验：签名不符/篡改/过期/格式错误一律返回空。 */
    public Optional<Claims> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        String payloadB64 = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        // 常量时间比对，避免签名比对的时序侧信道
        if (!MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8),
                sign(payloadB64).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            Claims c = MAPPER.readValue(B64D.decode(payloadB64), Claims.class);
            if (c.exp() <= System.currentTimeMillis() / 1000) {
                return Optional.empty();
            }
            return Optional.of(c);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String sign(String payloadB64) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(payloadB64.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }
}
```

- [ ] **Step 4: 加配置项**

在 `wenjin-server/src/main/resources/application.yml` 末尾（与 `wenjin.ai` 同级的 `wenjin:` 段下）加：
```yaml
wenjin:
  auth:
    secret: ${WENJIN_AUTH_SECRET:dev-secret-change-me-in-prod}
    ttl-seconds: 604800   # 令牌有效期 7 天
```
> 注：若 `wenjin:` 段已存在（`wenjin.ai.*`），把 `auth:` 并入同一个 `wenjin:` 下，勿重复顶层键。

- [ ] **Step 5: 运行测试确认通过**

Run: `cd wenjin-server && ./mvnw -q -Dtest=TokenServiceTest test`
Expected: PASS（5 个用例）。

- [ ] **Step 6: 提交**

```bash
git add wenjin-server/src/main/java/com/wenjin/security/TokenService.java \
        wenjin-server/src/test/java/com/wenjin/security/TokenServiceTest.java \
        wenjin-server/src/main/resources/application.yml
git commit -m "feat(auth): TokenService 签发/校验 HMAC 签名令牌（无新依赖）"
```

---

### Task 2: 密码 BCrypt 哈希 + 登录签发令牌

**Files:**
- Modify: `wenjin-server/pom.xml`（加 `spring-security-crypto`）
- Create: `wenjin-server/src/main/java/com/wenjin/config/PasswordEncoderConfig.java`
- Create: `wenjin-server/src/main/java/com/wenjin/dto/LoginVO.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/service/UserService.java`（`login` 返回 `LoginVO`）
- Modify: `wenjin-server/src/main/java/com/wenjin/service/impl/UserServiceImpl.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/controller/UserController.java`（`login` 返回 `Result<LoginVO>`）
- Test: `wenjin-server/src/test/java/com/wenjin/service/UserServiceImplTest.java`（改造）

**Interfaces:**
- Consumes: `TokenService.issue(long,int)`（Task 1）
- Produces:
  - `dto/LoginVO { String token; UserVO user; }`（Lombok `@Data`）
  - `UserService.login(LoginRequest) -> LoginVO`
  - 注册落库密码、登录比对均为 BCrypt；遗留明文登录成功时重哈希落库

- [ ] **Step 1: 加依赖**

`wenjin-server/pom.xml` 的 `<dependencies>` 内加（版本交给 Boot BOM）：
```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>
```

- [ ] **Step 2: 写/改失败测试**

把 `UserServiceImplTest` 改造为用真实 `BCryptPasswordEncoder` + 真实 `TokenService` 手工构造 service（替换 `@InjectMocks`）。完整替换文件内容：
```java
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
        verify(userMapper, never()).updateById(any()); // 已是哈希，不升级
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
```

- [ ] **Step 3: 运行确认失败**

Run: `cd wenjin-server && ./mvnw -q -Dtest=UserServiceImplTest test`
Expected: 编译失败（`LoginVO` 不存在、`UserServiceImpl` 构造器签名不符）。

- [ ] **Step 4: 建 LoginVO + PasswordEncoder bean**

`wenjin-server/src/main/java/com/wenjin/dto/LoginVO.java`：
```java
package com.wenjin.dto;

import lombok.Data;

/** 登录返回：令牌 + 用户信息（不含密码）。 */
@Data
public class LoginVO {
    private String token;
    private UserVO user;
}
```

`wenjin-server/src/main/java/com/wenjin/config/PasswordEncoderConfig.java`：
```java
package com.wenjin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 仅暴露 BCrypt 密码编码器；用的是 spring-security-crypto 的密码学工具，
 * 不引入 spring-security 过滤链，不影响现有拦截器鉴权。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

- [ ] **Step 5: 改 UserService 接口**

`wenjin-server/src/main/java/com/wenjin/service/UserService.java`：把 `import com.wenjin.dto.UserVO;` 旁加 `import com.wenjin.dto.LoginVO;`，并将
```java
    UserVO login(LoginRequest request);
```
改为
```java
    LoginVO login(LoginRequest request);
```

- [ ] **Step 6: 改 UserServiceImpl**

`wenjin-server/src/main/java/com/wenjin/service/impl/UserServiceImpl.java`：

(a) 顶部加 import：
```java
import com.wenjin.dto.LoginVO;
import com.wenjin.security.TokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
```

(b) 字段与构造器改为：
```java
    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public UserServiceImpl(SysUserMapper userMapper, PasswordEncoder passwordEncoder,
                           TokenService tokenService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }
```

(c) `register` 内把 `user.setPassword(request.getPassword());` 改为：
```java
        user.setPassword(passwordEncoder.encode(request.getPassword()));
```

(d) 整个 `login` 方法替换为：
```java
    @Override
    public LoginVO login(LoginRequest request) {
        // 按用户名查（密码不再进 SQL 条件，改为应用层哈希比对）
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, request.getUsername()));
        if (user == null || !passwordMatches(request.getPassword(), user)) {
            throw new BusinessException(ResultCode.LOGIN_FAIL, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.LOGIN_FAIL, "账号已被禁用");
        }
        LoginVO vo = new LoginVO();
        vo.setToken(tokenService.issue(user.getId(), user.getRole() == null ? 0 : user.getRole()));
        vo.setUser(toVO(user));
        return vo;
    }

    /**
     * 密码比对：bcrypt 哈希走 encoder；遗留明文按相等比对，命中则顺手重哈希落库（透明升级）。
     */
    private boolean passwordMatches(String raw, SysUser user) {
        String stored = user.getPassword();
        if (stored != null && stored.startsWith("$2")) {
            return passwordEncoder.matches(raw, stored);
        }
        boolean ok = raw != null && raw.equals(stored);
        if (ok) {
            user.setPassword(passwordEncoder.encode(raw));
            userMapper.updateById(user);
        }
        return ok;
    }
```

- [ ] **Step 7: 改 UserController**

`wenjin-server/src/main/java/com/wenjin/controller/UserController.java`：把 `import com.wenjin.dto.UserVO;` 旁加 `import com.wenjin.dto.LoginVO;`，并将
```java
    public Result<UserVO> login(@RequestBody LoginRequest request) {
```
改为
```java
    public Result<LoginVO> login(@RequestBody LoginRequest request) {
```

- [ ] **Step 8: 运行确认通过**

Run: `cd wenjin-server && ./mvnw -q -Dtest=UserServiceImplTest test`
Expected: PASS（注册哈希、bcrypt 登录返 token、遗留明文升级、错密码/未知/禁用、getUserById）。

- [ ] **Step 9: 提交**

```bash
git add wenjin-server/pom.xml \
        wenjin-server/src/main/java/com/wenjin/dto/LoginVO.java \
        wenjin-server/src/main/java/com/wenjin/config/PasswordEncoderConfig.java \
        wenjin-server/src/main/java/com/wenjin/service/UserService.java \
        wenjin-server/src/main/java/com/wenjin/service/impl/UserServiceImpl.java \
        wenjin-server/src/main/java/com/wenjin/controller/UserController.java \
        wenjin-server/src/test/java/com/wenjin/service/UserServiceImplTest.java
git commit -m "feat(auth): 密码改 BCrypt（遗留明文登录时透明升级）+ 登录签发令牌返回 LoginVO"
```

---

### Task 3: AuthContextInterceptor 改验 Bearer 令牌

**Files:**
- Modify: `wenjin-server/src/main/java/com/wenjin/config/AuthContextInterceptor.java`
- Test: `wenjin-server/src/test/java/com/wenjin/config/AuthContextInterceptorTest.java`（改造）
- Test: `wenjin-server/src/test/java/com/wenjin/controller/StudentAuthChainIntegrationTest.java`（改造：构造器入参 + 用真实令牌）

**Interfaces:**
- Consumes: `TokenService.verify(String)`、`TokenService.issue(long,int)`（Task 1）；`CurrentUser.set/get/clear`
- Produces: 合法 `Authorization: Bearer <token>` → `CurrentUser` 设为令牌 uid；无效/缺失 → 保持匿名仍 `return true`

- [ ] **Step 1: 改 AuthContextInterceptorTest（表达新契约）**

完整替换 `wenjin-server/src/test/java/com/wenjin/config/AuthContextInterceptorTest.java`：
```java
package com.wenjin.config;

import com.wenjin.security.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthContextInterceptor 直测：合法 Bearer 令牌 → CurrentUser 已设；
 * 无头/伪造/过期 → 不设但仍放行；afterCompletion 清理；OPTIONS 跳过。
 */
class AuthContextInterceptorTest {

    private final TokenService tokenService = new TokenService("test-secret", 3600);
    private final MockHttpServletResponse resp = new MockHttpServletResponse();

    private AuthContextInterceptor interceptor() {
        return new AuthContextInterceptor(tokenService);
    }

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    @Test
    void validBearer_setsCurrentUser() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        req.addHeader("Authorization", "Bearer " + tokenService.issue(2L, 2));

        boolean result = interceptor().preHandle(req, resp, new Object());

        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isEqualTo(2L);
    }

    @Test
    void noHeader_anonymous_stillTrue() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        boolean result = interceptor().preHandle(req, resp, new Object());
        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void forgedToken_anonymous_stillTrue() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        req.addHeader("Authorization", "Bearer not.a.realtoken");
        boolean result = interceptor().preHandle(req, resp, new Object());
        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void expiredToken_anonymous() {
        TokenService past = new TokenService("test-secret", -10);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        req.addHeader("Authorization", "Bearer " + past.issue(2L, 2));
        interceptor().preHandle(req, resp, new Object());
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void afterCompletion_clears() {
        CurrentUser.set(2L);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/graph/5");
        interceptor().afterCompletion(req, resp, new Object(), null);
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void optionsPreflight_skips() {
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/companion/chat");
        boolean result = interceptor().preHandle(req, resp, new Object());
        assertThat(result).isTrue();
        assertThat(CurrentUser.get()).isNull();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd wenjin-server && ./mvnw -q -Dtest=AuthContextInterceptorTest test`
Expected: 编译失败（`AuthContextInterceptor` 构造器仍要 `SysUserMapper`）。

- [ ] **Step 3: 改 AuthContextInterceptor**

完整替换 `wenjin-server/src/main/java/com/wenjin/config/AuthContextInterceptor.java`：
```java
package com.wenjin.config;

import com.wenjin.security.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 轻量认证上下文拦截器，挂载 {@code /api/**}。
 *
 * <p>从 {@code Authorization: Bearer <token>} 校验令牌（{@link TokenService}），
 * 通过则把令牌 uid 写入 {@link CurrentUser}；无头/伪造/过期一律保持匿名、不抛异常——
 * 是否拒绝交由各端点的 {@code AccessGuard.assertSelf} 按需决定。</p>
 *
 * <p>{@code afterCompletion} 清除 {@link CurrentUser}，防 ThreadLocal 泄漏到线程池复用的下一个请求。</p>
 */
@Component
public class AuthContextInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";

    private final TokenService tokenService;

    public AuthContextInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            tokenService.verify(header.substring(BEARER.length()).trim())
                    .ifPresent(claims -> CurrentUser.set(claims.uid()));
        }
        // 始终放行；鉴权拒绝由 AccessGuard.assertSelf 在各端点按需触发
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CurrentUser.clear();
    }
}
```

- [ ] **Step 4: 改 StudentAuthChainIntegrationTest（构造器 + 真实令牌）**

在 `wenjin-server/src/test/java/com/wenjin/controller/StudentAuthChainIntegrationTest.java` 做如下改动：

(a) import 调整：删 `import com.wenjin.entity.SysUser;` 与 `import com.wenjin.mapper.SysUserMapper;`，加 `import com.wenjin.security.TokenService;`。

(b) 删掉 `@Mock SysUserMapper userMapper;` 字段与 `user(long)` 辅助方法；加字段：
```java
    private final TokenService tokenService = new TokenService("test-secret", 3600);
```

(c) `setup()` 改为（去掉 userMapper stub，拦截器换构造器）：
```java
    @BeforeEach
    void setup() {
        DiagnosticController controller =
                new DiagnosticController(diagnosticService, diagnosticResultService, courseService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new AuthContextInterceptor(tokenService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
```

(d) 把所有 `.header("X-User-Id", "2")` 改为 `.header("Authorization", "Bearer " + tokenService.issue(2L, 2))`；`.header("X-User-Id", "9")` 改为 `.header("Authorization", "Bearer " + tokenService.issue(9L, 2))`。无头的用例保持无头。

(e) 新增一个伪造令牌用例（贴在 `getResult_noUserIdHeader_returns401` 后）：
```java
    @Test
    void getResult_forgedToken_returns401() throws Exception {
        mvc.perform(get("/api/diagnostic/result")
                        .header("Authorization", "Bearer forged.token.value")
                        .param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }
```
> `@MockitoSettings(LENIENT)` 保留；`diagnosticService/diagnosticResultService/courseService` 仍为 `@Mock`。

- [ ] **Step 5: 运行确认通过**

Run: `cd wenjin-server && ./mvnw -q -Dtest=AuthContextInterceptorTest,StudentAuthChainIntegrationTest test`
Expected: PASS（含伪造令牌 → 401）。

- [ ] **Step 6: 提交**

```bash
git add wenjin-server/src/main/java/com/wenjin/config/AuthContextInterceptor.java \
        wenjin-server/src/test/java/com/wenjin/config/AuthContextInterceptorTest.java \
        wenjin-server/src/test/java/com/wenjin/controller/StudentAuthChainIntegrationTest.java
git commit -m "feat(auth): AuthContextInterceptor 改验 Bearer 令牌填 CurrentUser（移除裸 X-User-Id）"
```

---

### Task 4: TeacherAuthInterceptor 与 TeacherCourseController 改读 CurrentUser

**Files:**
- Modify: `wenjin-server/src/main/java/com/wenjin/config/TeacherAuthInterceptor.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/controller/TeacherCourseController.java`
- Test: `wenjin-server/src/test/java/com/wenjin/config/TeacherAuthInterceptorTest.java`（改造）
- Test: `wenjin-server/src/test/java/com/wenjin/controller/TeacherCourseControllerTest.java`（改造 create 用例）

**Interfaces:**
- Consumes: `CurrentUser.get()`（由 Task 3 的 AuthContextInterceptor 在 `/api/**` 先行设置）
- Produces: 教师路由读已验证的 uid 查角色（role≠1→403，缺身份→401）；建课 owner 取自 `CurrentUser`

- [ ] **Step 1: 改 TeacherAuthInterceptorTest（新契约：设 CurrentUser 而非头）**

完整替换 `wenjin-server/src/test/java/com/wenjin/config/TeacherAuthInterceptorTest.java`：
```java
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
```

- [ ] **Step 2: 运行确认失败**

Run: `cd wenjin-server && ./mvnw -q -Dtest=TeacherAuthInterceptorTest test`
Expected: FAIL（现拦截器仍读 header，`noCurrentUser_throwsUnauthorized` 在无头时确实抛，但 `teacher_passes` 因没设 header 会抛 401 → 失败）。

- [ ] **Step 3: 改 TeacherAuthInterceptor**

完整替换 `wenjin-server/src/main/java/com/wenjin/config/TeacherAuthInterceptor.java`：
```java
package com.wenjin.config;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 教师端 / 管理端鉴权拦截器。
 *
 * <p>身份取自 {@link CurrentUser}（由 {@link AuthContextInterceptor} 校验 Bearer 令牌后设置，
 * 已不可伪造）。据此回查数据库取真实角色：仅 {@code role == 1}（教师）放行，
 * 缺身份 → 401，已登录但非教师 / 用户不存在 → 403/401。</p>
 */
@Component
public class TeacherAuthInterceptor implements HandlerInterceptor {

    /** 教师角色值（与 SysUser.role 约定一致：1=teacher, 2=student）。 */
    private static final int ROLE_TEACHER = 1;

    private final SysUserMapper userMapper;

    public TeacherAuthInterceptor(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        Long userId = CurrentUser.get();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage());
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage());
        }
        if (user.getRole() == null || user.getRole() != ROLE_TEACHER) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无访问权限：仅教师可访问该功能");
        }
        return true;
    }
}
```

- [ ] **Step 4: 改 TeacherCourseController（owner 取自 CurrentUser）**

`wenjin-server/src/main/java/com/wenjin/controller/TeacherCourseController.java`：
(a) 顶部加 `import com.wenjin.config.CurrentUser;`；删除 `org.springframework.web.bind.annotation.RequestHeader` 的 import（若仅此一处用）。
(b) `create` 改为：
```java
    @PostMapping
    public Result<TeacherCourseVO> create(@RequestBody CreateCourseRequest req) {
        // owner 取自已验证的当前用户（教师拦截器已确保是教师）
        Long teacherId = CurrentUser.get();
        return Result.ok(teacherCourseService.create(req.getName(), teacherId));
    }
```

- [ ] **Step 5: 改 TeacherCourseControllerTest 的 create 用例**

`wenjin-server/src/test/java/com/wenjin/controller/TeacherCourseControllerTest.java`：
(a) 顶部加 `import com.wenjin.config.CurrentUser;`、`import org.junit.jupiter.api.AfterEach;`。
(b) 类内加：
```java
    @AfterEach
    void clear() { CurrentUser.clear(); }
```
(c) `create_passesNameAndHeaderTeacherId` 改名并改体：
```java
    @Test
    void create_passesNameAndCurrentUserTeacherId() {
        AtomicReference<String> seenName = new AtomicReference<>();
        AtomicReference<Long> seenTeacher = new AtomicReference<>();
        TeacherCourseService fake = new TeacherCourseService() {
            public List<TeacherCourseVO> list() { return List.of(); }
            public TeacherCourseVO create(String name, Long teacherId) {
                seenName.set(name); seenTeacher.set(teacherId);
                return new TeacherCourseVO(1L, "ABCDEF0123", name);
            }
            public void delete(Long courseId) {}
            public void setPublished(Long courseId, boolean published) {}
        };
        TeacherCourseController controller = new TeacherCourseController(fake);
        CurrentUser.set(9L);

        CreateCourseRequest req = new CreateCourseRequest();
        req.setName("新课");
        Result<TeacherCourseVO> res = controller.create(req);

        assertThat(seenName.get()).isEqualTo("新课");
        assertThat(seenTeacher.get()).isEqualTo(9L);
        assertThat(res.getData().getCode()).isEqualTo("ABCDEF0123");
    }
```

- [ ] **Step 6: 运行确认通过**

Run: `cd wenjin-server && ./mvnw -q -Dtest=TeacherAuthInterceptorTest,TeacherCourseControllerTest test`
Expected: PASS。

- [ ] **Step 7: 全量回归 + 提交**

Run: `cd wenjin-server && ./mvnw -q test`
Expected: 全绿（确认没有别处再读 X-User-Id；若有编译/测试失败按提示修到本设计语义）。
```bash
git add wenjin-server/src/main/java/com/wenjin/config/TeacherAuthInterceptor.java \
        wenjin-server/src/main/java/com/wenjin/controller/TeacherCourseController.java \
        wenjin-server/src/test/java/com/wenjin/config/TeacherAuthInterceptorTest.java \
        wenjin-server/src/test/java/com/wenjin/controller/TeacherCourseControllerTest.java
git commit -m "feat(auth): 教师拦截器与建课 owner 改读已验证的 CurrentUser（移除 X-User-Id）"
```

---

### Task 5: 前端 http.js 改带 Authorization + 401 跳登录

**Files:**
- Modify: `wenjin-web/src/api/http.js`

**Interfaces:**
- Consumes: `localStorage.wj_token`（由 Task 6 的登录流程写入）
- Produces: 所有 axios 请求带 `Authorization: Bearer <token>`；响应 `code===401` 时清登录态并跳 `/`

- [ ] **Step 1: 改请求拦截器**

`wenjin-web/src/api/http.js` 把请求拦截器整段替换为：
```js
// 请求拦截器：带上登录令牌（Bearer）。SSE 走原生 fetch 不经此处，见 companion.js。
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('wj_token')
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})
```

- [ ] **Step 2: 改响应拦截器（401 清态跳登录）**

把响应拦截器成功分支替换为：
```js
http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data
      if (body.code === 401) {
        // 令牌缺失/失效：清登录态，跳登录页（路由路径为 '/'）。避免在登录页自跳。
        localStorage.removeItem('wj_token')
        localStorage.removeItem('wj_user')
        if (window.location.pathname !== '/') window.location.assign('/')
      }
      const err = new Error(body.message || '请求失败')
      err.code = body.code
      err.detail = body.data
      return Promise.reject(err)
    }
    return body
  },
  (error) => Promise.reject(error)
)
```

- [ ] **Step 3: 构建确认通过**

Run: `cd wenjin-web && npm run build`
Expected: 构建成功（exit 0）。

- [ ] **Step 4: 提交**

```bash
git add wenjin-web/src/api/http.js
git commit -m "feat(web): http.js 改带 Authorization: Bearer + 401 清态跳登录"
```

---

### Task 6: 前端 SSE/登录/登出 接入令牌

**Files:**
- Modify: `wenjin-web/src/api/companion.js`（SSE 头）
- Modify: `wenjin-web/src/views/Login.vue`（存令牌、注册后登录、登出清令牌）
- Modify: `wenjin-web/src/components/TopBar.vue`（登出清令牌）

**Interfaces:**
- Consumes: `apiLogin` 现返回 `LoginVO { token, user }`（Task 2）；`localStorage.wj_token`（Task 5）
- Produces: SSE 带 `Authorization` 头；登录/注册写 `wj_token`+`wj_user`；登出清两者

- [ ] **Step 1: 改 companion.js SSE 头**

`wenjin-web/src/api/companion.js` 把上一轮加的 `X-User-Id` 块替换为 Bearer：
```js
    // SSE 用原生 fetch，绕过 http.js 的 axios 拦截器——必须手动补 Authorization，
    // 否则后端 AccessGuard.assertSelf 取不到当前用户，整条流式对话会 401。
    const headers = {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream'
    }
    const token = localStorage.getItem('wj_token')
    if (token) headers['Authorization'] = `Bearer ${token}`
```
（保持其后 `fetch('/api/companion/chat', { method:'POST', headers, body: ... })` 不变。）

- [ ] **Step 2: 改 Login.vue 登录/演示登录/注册/登出**

`wenjin-web/src/views/Login.vue`：

(a) `handleLogin` 内：
```js
    const data = await apiLogin({ username: sid.value, password: pwd.value })
    currentUser.value = data.user
    localStorage.setItem('wj_token', data.token)
    localStorage.setItem('wj_user', JSON.stringify(data.user))
    step.value = 'course'
    loadCourses()
```

(b) `handleDemoLogin` 内 `.then` 回调：
```js
    .then(data => {
      currentUser.value = data.user
      localStorage.setItem('wj_token', data.token)
      localStorage.setItem('wj_user', JSON.stringify(data.user))
      step.value = 'course'
      loadCourses()
    })
```

(c) `handleRegister` 内（注册成功后再登录拿令牌；`apiRegister` 仍返回 UserVO）：
```js
    await apiRegister({
      username: regUsername.value,
      password: regPassword.value,
      realName: regRealName.value,
      role: regRole.value
    })
    // 注册不发令牌，紧接着用同一凭据登录拿令牌（保持"注册即进入"的体验）
    const data = await apiLogin({ username: regUsername.value, password: regPassword.value })
    currentUser.value = data.user
    localStorage.setItem('wj_token', data.token)
    localStorage.setItem('wj_user', JSON.stringify(data.user))
    step.value = 'course'
    loadCourses()
```

(d) `handleLogout` 内在 `localStorage.removeItem('wj_user')` 旁加：
```js
  localStorage.removeItem('wj_token')
```

- [ ] **Step 3: 改 TopBar.vue 登出**

`wenjin-web/src/components/TopBar.vue` 第 134 行 `localStorage.removeItem('wj_user')` 后加一行：
```js
  localStorage.removeItem('wj_token')
```

- [ ] **Step 4: 构建确认通过**

Run: `cd wenjin-web && npm run build`
Expected: 构建成功（exit 0）。

- [ ] **Step 5: 提交**

```bash
git add wenjin-web/src/api/companion.js wenjin-web/src/views/Login.vue wenjin-web/src/components/TopBar.vue
git commit -m "feat(web): SSE/登录/注册/登出 接入 Bearer 令牌（存取 wj_token）"
```

---

### Task 7: 全量验证 + 真机冒烟

**Files:** 无（验证任务）

- [ ] **Step 1: 后端全量测试**

Run: `cd wenjin-server && ./mvnw -q test`
Expected: 全绿，0 失败 0 错误（应为 301 + 新增 TokenServiceTest 5 + AuthContext 改造后用例 + 等）。用 surefire 统计：
Run: `awk -F'"' '/<testsuite /{for(i=1;i<=NF;i++){if($(i-1)~/ tests=/)t+=$i; if($(i-1)~/failures=/)f+=$i; if($(i-1)~/errors=/)e+=$i}} END{printf "tests=%d failures=%d errors=%d\n",t,f,e}' wenjin-server/target/surefire-reports/*.xml`
Expected: `failures=0 errors=0`。

- [ ] **Step 2: 前端构建**

Run: `cd wenjin-web && npm run build`
Expected: exit 0。

- [ ] **Step 3: 真机起服务**

Run: `cd wenjin-server && ./mvnw -q -DskipTests clean package`，然后后台 `java -jar target/wenjin-server.jar`（MySQL 须在 localhost:3306/wenjin root/root；起服务细节见记忆 wenjin-build-env）。轮询 `http://localhost:8080/api/companion/conversations?studentId=2&courseId=5` 返回非 000 即就绪。

- [ ] **Step 4: 真机冒烟（令牌闭环）**

逐条执行（demo_student 明文口令为 `demo`）：
```bash
# 1) 登录拿令牌
TOK=$(curl -s -X POST http://localhost:8080/api/login -H 'Content-Type: application/json' \
  -d '{"username":"demo_student","password":"demo"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
echo "token=$TOK"   # 非空即登录成功

# 2) 带令牌访问本人学情 → code:0
curl -s -H "Authorization: Bearer $TOK" \
  "http://localhost:8080/api/companion/conversations?studentId=2&courseId=5" | head -c 200; echo

# 3) 无令牌 → code:401
curl -s "http://localhost:8080/api/companion/conversations?studentId=2&courseId=5"; echo

# 4) 伪造令牌 → code:401
curl -s -H "Authorization: Bearer forged.token.value" \
  "http://localhost:8080/api/companion/conversations?studentId=2&courseId=5"; echo

# 5) 拿 demo_student 令牌访问他人(studentId=3) → code:403
curl -s -H "Authorization: Bearer $TOK" \
  "http://localhost:8080/api/companion/conversations?studentId=3&courseId=5"; echo

# 6) 教师令牌走教师路由：登录 demo_teacher 取令牌后访问 /api/teacher/courses → code:0；用学生令牌 → code:403
TTOK=$(curl -s -X POST http://localhost:8080/api/login -H 'Content-Type: application/json' \
  -d '{"username":"demo_teacher","password":"demo"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
curl -s -H "Authorization: Bearer $TTOK" http://localhost:8080/api/teacher/courses | head -c 120; echo
curl -s -H "Authorization: Bearer $TOK"  http://localhost:8080/api/teacher/courses; echo
```
Expected: 2)→`code:0`；3)4)→`code:401`；5)→`code:403`；6) 教师→`code:0`、学生令牌→`code:403`。

- [ ] **Step 5: 验证遗留明文已升级**

Run: `"/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql" -uroot -proot -D wenjin -N -e "SELECT id,LEFT(password,4) FROM sys_user WHERE username IN ('demo_student','demo_teacher');"`
Expected: 两行 password 前缀均为 `$2`（首次登录后已升级为 bcrypt）。

- [ ] **Step 6: 停服务 + 清理**

停掉 8080 的 java 进程。若冒烟产生了脏数据（如新会话），按上一里程碑做法清理。

- [ ] **Step 7: 收尾提交（如有）**

本任务通常无代码改动；若 Step 1/2 暴露问题并修复，按对应任务语义提交。

---

## Self-Review（写完计划后自查）

**1. Spec coverage（spec 各节是否都有任务）**
- §2 令牌设计 → Task 1。
- §3.1 密码哈希/升级 → Task 2。§3.2 LoginVO → Task 2。§3.3 AuthContextInterceptor → Task 3；TeacherAuthInterceptor → Task 4。
- §4 前端 http.js/companion.js/Login.vue/登出 → Task 5、6。
- §5 测试 → 各任务内含 + Task 7。§6 迁移（登录升级、无脚本） → Task 2 + Task 7 Step 5。§7 遗留 → 不实现（记录在案）。
- 额外覆盖（spec 未显式列但探查到）：`TeacherCourseController` 读 X-User-Id → Task 4 一并迁移。

**2. Placeholder scan:** 无 TBD/TODO；每个改码步骤均给出完整代码。

**3. Type consistency:** `TokenService(secret, ttl)` / `issue(long,int)` / `verify→Optional<Claims>` / `Claims(uid,role,exp)` 在 Task 1/2/3 一致；`LoginVO{token,user}` 在 Task 2/6 一致；`UserServiceImpl(userMapper, passwordEncoder, tokenService)` 构造器在 Task 2 实现、Task 2 测试一致；`AuthContextInterceptor(TokenService)` 在 Task 3 实现与两处测试一致；`TeacherCourseController.create(req)` 在 Task 4 控制器与测试一致。
