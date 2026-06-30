# 问津（Fordo）认证里程碑设计 — 令牌认证 + 密码哈希

> 日期：2026-06-30
> 适用仓库：`wenjin-server`（Spring Boot 3 / Java 21 / MyBatis-Plus）、`wenjin-web`（Vue 3 + Vite）
> 背景：代码评审修复 T1–T6 与同日安全复盘只解决了**授权（authz）**；本里程碑解决遗留的**认证（authn）**——`X-User-Id` 明文可伪造 + 密码明文存储两大洞。
> 档次：A 档（无状态签名令牌，无服务端吐销）+ BCrypt 密码哈希。已与用户确认。

---

## 1. 目标与边界

### 解决
1. **身份可伪造**：当前 `AuthContextInterceptor` 仅校验 `X-User-Id` 头对应用户"存在"，任何登录者改头即可冒充他人。改为服务端签发、客户端无法伪造的**签名令牌**，每请求校验。
2. **密码明文**：`UserServiceImpl` 注册存明文、登录用明文 `eq` 比对。改为 **BCrypt** 哈希存储与比对，遗留明文用户**登录时透明升级**。

### 不改（明确排除，避免范围蔓延）
- 现有授权层一律不动：`AccessGuard.assertSelf`、会话归属 `assertConversationOwner`、写端点守卫、`TeacherAuthInterceptor` 的"role==1"语义、"匿名放行、端点自决"模型、匿名看图谱（`studentId` 为 null）。
- 不做刷新令牌、不做服务端吐销/一键下线、不引入 Redis/会话表、不开启 Spring Security 过滤链、不做库表结构变更。
- 不做 HTTPS/反代/CORS 收紧（仍属上线前事项，单列）。

### 成功标准
- 携带服务端签发的有效令牌 → 请求按本人身份通过；伪造/篡改/过期/无令牌 → 需要身份的端点返回 401（业务码），教师路由非教师令牌 → 403。
- `sys_user.password` 不再出现明文（新注册即哈希；老用户首次登录后升级为 `$2` 哈希）。
- 全量 `mvn test` 绿、前端 `npm run build` 绿、真机冒烟通过。

---

## 2. 令牌设计（无新依赖，JDK 自带 HMAC）

- **格式**：`payloadB64.signatureB64`（极简 JWS，两段式）。
  - `payload` = JSON `{"uid":<Long>,"role":<Integer>,"exp":<epochSeconds>}`，再 base64url **无填充**编码为 `payloadB64`。
  - `signature` = `HMAC-SHA256(secretBytes, payloadB64Bytes)`，base64url 无填充为 `signatureB64`。
- **密钥**：配置项 `wenjin.auth.secret`，提供 dev 默认值，可用环境变量 `WENJIN_AUTH_SECRET` 覆盖。更换密钥即让所有已签发令牌失效（可接受）。
- **有效期**：`wenjin.auth.ttl-seconds` 默认 `604800`（7 天）。签发时 `exp = now + ttl`。
- **校验流程**：
  1. 按 `.` 拆成两段，缺段/空段 → 无效。
  2. 对 `payloadB64` 重算 HMAC，与 `signatureB64` 做**常量时间比对**（`java.security.MessageDigest.isEqual` on raw bytes）。
  3. 不匹配 → 无效（伪造/篡改/错密钥都落在此）。
  4. 解析 `payload` JSON，`exp <= now` → 无效（过期）。
  5. 通过则返回 `{uid, role}`。
- **新单元 `com.wenjin.security.TokenService`**：
  - `String issue(long uid, int role)`、`Optional<Claims> verify(String token)`（`Claims` 为内部小记录 `record Claims(long uid, int role, long exp)`）。
  - 构造接收 `secret` 与 `ttlSeconds`（`@Value` 注入），**不依赖其它 bean**，可像 `buildSystemPrompt` 那样纯单测（构造时直接传值或用 `ReflectionTestUtils`）。
  - JSON 序列化复用项目已在用的 Jackson（`ObjectMapper`），不新引库。

---

## 3. 后端改动

### 3.1 密码哈希（`spring-security-crypto`，唯一新依赖）
- `wenjin-server/pom.xml` 增 `org.springframework.security:spring-security-crypto`（仅密码学工具，不激活 Security 过滤链；版本随 Spring Boot BOM 管理，不写死）。
- 新增 `@Configuration` 暴露 `BCryptPasswordEncoder` bean（或在 `UserServiceImpl` 内 `new BCryptPasswordEncoder()` 字段——倾向 bean，便于注入与测试）。
- `UserServiceImpl.register`：`user.setPassword(encoder.encode(raw))`。
- `UserServiceImpl.login`（透明升级）：
  1. 仅按 `username` 查用户（不再把 password 放进查询条件）。
  2. 用户不存在 / 禁用 → 原 `LOGIN_FAIL` / 禁用文案。
  3. `stored = user.getPassword()`：
     - `stored` 以 `$2` 开头 → `encoder.matches(raw, stored)`，false 则 `LOGIN_FAIL`。
     - 否则（遗留明文）→ `raw.equals(stored)`，false 则 `LOGIN_FAIL`；**true 则 `user.setPassword(encoder.encode(raw))` 并 `userMapper.updateById(user)` 升级落库**。
  4. 校验通过 → 签发令牌，返回 `LoginVO`。

### 3.2 登录返回体
- 新增 `dto/LoginVO`：`{ String token; UserVO user; }`。
- `UserService.login` 返回类型由 `UserVO` 改为 `LoginVO`；`UserController.login` 随之改。
- `register` 维持返回 `UserVO`（不自动登录）。

### 3.3 拦截器
- **`AuthContextInterceptor`（`/api/**`）**：
  - `preHandle`：放行 `OPTIONS`；读 `Authorization` 头，去掉 `Bearer ` 前缀，`TokenService.verify`；成功 `CurrentUser.set(claims.uid())`；任何失败保持匿名、仍 `return true`（下游契约不变）。
  - **移除**对 `SysUserMapper` 的每请求查库与对 `X-User-Id` 的读取。
  - `afterCompletion` 仍 `CurrentUser.clear()`。
- **`TeacherAuthInterceptor`（`/api/teacher|admin/**`）**：
  - 改为读 `CurrentUser.get()`（已由上一拦截器从令牌验出的 uid）；null → 401。
  - 用该 uid 查 `SysUserMapper` 取 role（保持查库以反映禁用/改角色）；role≠1 → 403。
  - 移除对 `X-User-Id` 头的直接读取；`HEADER_USER_ID` 常量若再无引用则删除。
- 注册顺序不变（authContext 先、teacherAuth 后）。

---

## 4. 前端改动

- **`api/http.js`**：请求拦截器改为
  ```js
  const token = localStorage.getItem('wj_token')
  if (token) config.headers['Authorization'] = `Bearer ${token}`
  ```
  删除 `X-User-Id` 注入。
- **`api/companion.js`** SSE `fetch`：把上轮加的 `X-User-Id` 改为 `Authorization: Bearer <wj_token>`（原生 fetch 仍绕过 axios 拦截器，必须手动带；保留这条注释教训）。
- **`Login.vue`**：登录成功后 `data` 现为 `LoginVO` → `localStorage.setItem('wj_token', data.token)` + `localStorage.setItem('wj_user', JSON.stringify(data.user))`。
- **登出 / 401 处理**：
  - `http.js` 响应拦截器在"非零 code 抛错"分支里识别 `code===401`：清 `wj_token`+`wj_user`，并用 `window.location.assign('/login')` 跳转（用 `window.location` 而非 import router，避免拦截器与路由的循环依赖）；判断 `window.location.pathname !== '/login'` 才跳，避免循环。
  - 现有登出逻辑（TopBar 等）追加清 `wj_token`。

---

## 5. 测试

- **`TokenServiceTest`**（纯单测）：签发→校验往返取回 uid/role；篡改 payload（改一字节）→ 拒；过期（ttl 设负或构造过去 exp）→ 拒；错密钥校验 → 拒;格式非法（缺段/空串）→ 拒。
- **`UserServiceImplTest`**（Mockito）：
  - 注册：落库密码为 `$2` 前缀、`encoder.matches(raw, 落库值)` 为真。
  - 登录-bcrypt：匹配返回带 token 的 `LoginVO`、`TokenService.verify(token)` 得到该 uid。
  - 登录-遗留明文升级：stored 为明文、raw 相等 → 成功且断言 `updateById` 被调且新值为 `$2` 哈希。
  - 登录-错密码 → `LOGIN_FAIL`，不签发。
- **`AuthContextInterceptorTest`**（改造现有）：合法 Bearer → `CurrentUser` 已设为令牌 uid；伪造/过期/无头 → 不设、仍 `return true`；`OPTIONS` 跳过；`afterCompletion` 清理。
- **`StudentAuthChainIntegrationTest`（扩展）/ 教师链**：用真实 `TokenService` 发的令牌走 standalone MockMvc——本人令牌→0；他人令牌（uid≠studentId）→403；无/伪造令牌→401；教师路由用学生令牌→403、教师令牌→放行。
- **真机冒烟**（fat jar + MySQL）：`POST /api/login` 取 token；带 `Authorization` 访问学生端点→0；改一字节令牌→401；无头→401；用 demo_teacher 登录验证遗留明文升级（库里该行变 `$2`）。验毕清理测试副作用。

---

## 6. 落地与迁移

- **依赖**：仅 `spring-security-crypto` 一个 jar。
- **数据迁移**：无需脚本——登录时透明升级；三个现有用户（`demo_student`/`demo_teacher`/id 3）下次登录自动转哈希。`schema.sql`/seeder 中若有明文示例口令，注释说明"首次登录自动升级"。
- **配置**：`application.yml` 增 `wenjin.auth.secret` 与 `wenjin.auth.ttl-seconds`，dev 默认值 + 环境变量覆盖说明。
- **兼容性**：上线本里程碑后，旧的"裸 `X-User-Id`"客户端将一律 401——前后端需同批发布。

---

## 7. 仍然遗留（本里程碑之后）

- 无服务端吐销：被盗令牌在 `exp` 前持续有效（A 档取舍；如需，未来加会话表或令牌版本号即可升级）。
- HTTPS / 反代 / CORS 收紧仍为上线前事项。
- 令牌放 `localStorage` 存在 XSS 取走风险（与现有 `wj_user` 同等量级；彻底解决需 HttpOnly Cookie + CSRF，属更大改动，不在本里程碑）。
