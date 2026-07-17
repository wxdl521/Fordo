# 问津 · 生产部署与加固指南

> 目标读者：把问津从「演示就绪」推进到「公网生产可用」的部署/运维者。
> 现状速判：功能完整、后端 300+ 测试绿、认证已加固。**演示 / 内网试用** 设好密钥与 AI Key 即可上；**公网生产** 请先逐项完成下文「第 2 节 必做清单」。

本指南分两层：

- **A. 仓库 / 配置加固** —— CORS 外部化、生产 profile、Actuator 与迁移门禁均已落地；发布时按第 2 节核对环境配置。
- **B. 运维上线** —— 服务器、域名、HTTPS、nginx、生产 MySQL，依赖你的基础设施，本指南给出可照抄的示例。

公开注册**只能**创建学生账号；教师与管理员不得通过 `/api/register` 自助开通（详见第 2.3.1 节）。

---

## 1. 推荐部署架构

```
                       (HTTPS 443)
   浏览器  ───────────────────────────►  nginx
                                          │  ├── /        → 静态前端 dist/（Vue 打包产物）
                                          │  └── /api/**  → 反向代理
                                          ▼
                                   Spring Boot 后端 (127.0.0.1:8080, profile=prod)
                                          │
                                          ▼
                                     MySQL 8.0.16+ (专用账号，最小权限)
```

要点：前端构建为静态文件由 nginx 托管；`/api` 经反代到后端，**对外只暴露 nginx 443**，后端 8080 只监听本机；CORS 因此可彻底关闭（同源），或限定到前端域名。

---

## 2. 必做清单（上公网前逐项确认）

| # | 加固项 | 类型 | 状态 |
|---|--------|------|------|
| 2.1 | 令牌密钥 `WENJIN_AUTH_SECRET` + 启用 `prod` profile | 配置 | ☐ |
| 2.2 | 收紧 CORS（同源关闭跨域，或用 `WENJIN_CORS_ORIGINS` 限定域名） | 配置/架构 | ☐ |
| 2.3 | 数据库专用账号 + 删除演示弱口令账号 | 配置/SQL | ☐ |
| 2.3.1 | 角色模型：公开注册仅学生；首个 ADMIN 用一次性 bootstrap，其后用 ADMIN API / SQL 备份 | 运维/配置 | ☐ |
| 2.4 | 日志降噪（关 debug、关 SQL 打印） | 配置 | ☐ |
| 2.5 | 配置真实可用的 AI Key | 配置 | ☐ |
| 2.6 | 前端生产构建 + nginx 托管 | 运维 | ☐ |

### 2.1 令牌密钥 + 启用 prod profile（最关键）

**为什么**：令牌用 HMAC 密钥签名。默认值 `dev-secret-change-me-in-prod` 公开在仓库，**不覆盖则任何人可伪造任意用户（含教师/管理员）的令牌**，本项目的认证形同虚设。

**守卫行为**：`AuthSecretGuard` 仅在激活的 profile 非空且全部为 `dev`/`test` 时放宽检查。除显式 `dev`/`test` 外（包括无 profile、`prod` 或混合 profile），默认值、空值及 UTF-8 长度不足 32 字节的密钥都会 fail-fast。生产仍必须同时显式启用 `prod` 并配置强随机、至少 32 UTF-8 字节的密钥：

```bash
export SPRING_PROFILES_ACTIVE=prod
export WENJIN_AUTH_SECRET="$(openssl rand -base64 48)"   # 强随机，妥善保存，勿入库
```

**验证**：在无 profile 或 `prod` profile 下，若密钥缺失、仍为默认值或不足 32 UTF-8 字节，后端都应启动失败并报错中止——这正是预期的部署门禁。

> 令牌有效期 `wenjin.auth.ttl-seconds` 默认 604800（7 天），按需调整。

### 2.2 收紧 CORS（已配置化）

**现状**：`WebConfig` 已读取 `wenjin.cors.allowed-origin-patterns`；开发环境默认 `*`，`application-prod.yml` 要求通过 `WENJIN_CORS_ORIGINS` 指定生产来源。

**同源部署（推荐）**：前端与后端经 nginx 同源（都在 `https://你的域名` 下，`/api` 反代），浏览器不发跨域请求，最简单也最安全。

**前后端分域**：将逗号分隔的可信来源写入环境变量，不需要修改 Java 代码：

```yaml
wenjin:
  cors:
    allowed-origin-patterns: ${WENJIN_CORS_ORIGINS:https://你的前端域名}
```

令牌走 `Authorization` 头且不依赖 Cookie，服务端保持 `allowCredentials(false)`。禁止在生产把 `WENJIN_CORS_ORIGINS` 设为 `*`。

### 2.3 数据库账号分离（migrator / app）+ 受控迁移 + 演示弱口令

**禁止**在生产对已有库执行 `schema.sql`（含 `DROP TABLE`，会删数据）。Schema 只走 Flyway：`classpath:db/migration`。

#### 两类数据库账号

| 账号 | 权限 | 用途 |
| --- | --- | --- |
| `wenjin_migrator` | DDL + DML（`ALL` 或至少 CREATE/ALTER/INDEX + DML） | 发布流水线**一次性**迁移作业 |
| `wenjin`（app） | 仅 `SELECT, INSERT, UPDATE, DELETE` | 长期运行的应用实例，**无 DDL** |

```sql
CREATE DATABASE IF NOT EXISTS wenjin
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

-- 迁移账号（仅迁移作业使用，勿配进常驻应用）
CREATE USER 'wenjin_migrator'@'localhost' IDENTIFIED BY '足够强的随机密码_migrator';
GRANT ALL PRIVILEGES ON wenjin.* TO 'wenjin_migrator'@'localhost';

-- 应用账号（生产常驻）
CREATE USER 'wenjin'@'localhost' IDENTIFIED BY '足够强的随机密码_app';
GRANT SELECT, INSERT, UPDATE, DELETE ON wenjin.* TO 'wenjin'@'localhost';
FLUSH PRIVILEGES;
```

```bash
# 常驻应用：只用 app 账号，且不要开 Flyway
export DB_USER=wenjin
export DB_PASSWORD='足够强的随机密码_app'
# WENJIN_FLYWAY_ENABLED 不设置或 false
```

#### 发布迁移流程（失败即停）

配置要点（已在 `application.yml`）：

- `spring.flyway.enabled=${WENJIN_FLYWAY_ENABLED:false}`
- `baseline-on-migrate: true`、`baseline-version: 2`、`validate-on-migrate: true`
- 既有生产库（已含 m01/m02 结构）首次接入时 baseline 到 2，之后只跑 **V3+**
- 空库：作业会执行 V1（建表）+ V2（里程碑标记）+ 后续增量

推荐步骤：

1. **备份**（迁移前必做）
   ```bash
   mysqldump -u root -p --single-transaction --routines --triggers wenjin \
     > "wenjin-backup-$(date +%Y%m%d%H%M%S).sql"
   ```
2. **info**（确认待执行版本）
   ```bash
   export DB_USER=wenjin_migrator
   export DB_PASSWORD='足够强的随机密码_migrator'
   export WENJIN_FLYWAY_ENABLED=true
   # 使用与线上一致的 jar / 同一 classpath:db/migration
   java -jar wenjin-server.jar --spring.main.web-application-type=none \
     # 或在流水线中调用 Flyway CLI / 专用 migrate 入口
   # 等价检查：确认 flyway_schema_history 与待应用 Vn
   ```
   也可用 Flyway CLI（版本与 Boot BOM 对齐）：
   ```bash
   flyway -url=jdbc:mysql://localhost:3306/wenjin \
     -user=wenjin_migrator -password=... \
     -locations=filesystem:wenjin-server/src/main/resources/db/migration \
     -baselineOnMigrate=true -baselineVersion=2 \
     info
   ```
3. **migrate V3–V8 expand only**
   ```bash
   SPRING_FLYWAY_TARGET=8 WENJIN_FLYWAY_ENABLED=true java -jar wenjin-server.jar --spring.main.web-application-type=none
   ```
   V4 同时增加 `kg_node/kg_edge.archived_by_course` 与 CHECK 约束。启用图谱对账前必须确认两列存在且 `NOT NULL DEFAULT 0`；课程归档只标记当时活跃的图行，恢复课程不得复活此前独立归档的节点或边。
4. **校验 V9 前审计**：`flyway info` 精确到 V8 Success；运行 `ops/sql/security-role-audit.sql` 与 `ops/sql/preflight-integrity.sql`。所有 `bad_count` 必须为 0；`inventory_count` / `review_count` 可非 0，但必须记录复核结论。
5. **在独立发布窗口 migrate V9 contract only**
   ```bash
   SPRING_FLYWAY_TARGET=9 WENJIN_FLYWAY_ENABLED=true java -jar wenjin-server.jar --spring.main.web-application-type=none
   ```
6. **再启动** 普通应用实例（`DB_USER=wenjin`，`WENJIN_FLYWAY_ENABLED=false` 或不设）。

`--spring.main.web-application-type=none` 只是不启动 Web Server，不能保证 JVM 自行退出。一次性作业编排器必须在迁移成功后终止并回收进程/Pod；禁止把持有 DDL 凭据的 migrator 当作常驻应用。

**失败停止与回滚规则**：

- migrate 非 0 退出码 → **禁止**滚动应用新版本；保留旧实例。
- 不得在生产「重跑 schema.sql」或手工 `DROP` 救火。
- 回滚优先：恢复迁移前逻辑备份（mysqldump），再排查失败的 `Vn`；Flyway 默认不对已成功版本自动 undo。
- 修复脚本以**新的 Vn+1** 正向修补，避免改写已发布迁移的 checksum（`validate-on-migrate=true` 会拒绝篡改历史）。

V5/V6 预检返回 `SQLSTATE 45000` 时，恢复顺序不可改变：

1. 先查 `flyway_schema_history` 与 `information_schema`，确认没有半成品业务表 DDL；MySQL procedure/DDL 非事务，禁止未检查就直接 `flyway repair`。
2. 修复或隔离重复数据：V5 同时清零两组路径项重复，V6 清零 `course_id + SHA2(TRIM(stem), 256)` 重复。
3. 执行 `flyway repair` 移除 `success=0` 的 failed history。
4. 使用失败时**同一 target** 和 non-web 参数重跑迁移。
5. 重新执行 `flyway info`、重复数据检查和完整性 audit，确认全绿后再继续发布。

#### V9 关系完整性（独立发布窗口）

- **V3–V8** 为 expand（可与 wave-1 后端分波次上线）；**`V9__relational_integrity.sql` 必须单独发布**，不得与首次 expand 混在同一变更单强行合并，除非预发库所有 `bad_count` 为 0、inventory/review 已留档并书面确认。
- V9 前必跑只读审计：`ops/sql/security-role-audit.sql`、`ops/sql/preflight-integrity.sql`（任一 `bad_count > 0` → 人工修复，禁止硬上；非 0 的 `inventory_count` / `review_count` 只要求记录人工复核结论）。
- V9 内含 `SIGNAL 45000` 预检 + 命名物理外键 + `course.teacher_id NOT NULL` + `sys_user.role CHECK`；失败即整次 migrate 失败。
- `answer_record.session_id` 是按 `scene` 解释的多态逻辑兼容字段，可对应自适应诊断或练习会话，因此不物理绑定到单一 session 表；`fixed_diagnostic_session_id` 仍保留固定诊断物理外键。
- V9 后验收：`ops/sql/post-migration-verification.sql`（含归档来源列/CHECK 与标记一致性）。
- **备份与还原**：每次 migrate（含 V9）前 `mysqldump`；V9 失败或验收不通过时，优先 **restore 迁移前备份**，禁止用 `DROP TABLE` / 重跑 `schema.sql` 回滚。功能回滚优先关 feature flags（见 runbook）。
- 分阶段上线、观测与安全回滚细则见 **[docs/runbooks/project-hardening-rollout.md](docs/runbooks/project-hardening-rollout.md)**。

**演示弱口令**：生产**不要**加载 `data.sql`。本地演示账号 `demo_teacher` / `demo_student`（密码 `demo`）仅存在于 `data.sql`。

- **推荐：生产干净库** + 真实教师/管理员（见 2.3.1）。
- **或：库中已有演示账号则改强密**（勿直接删 `demo_teacher` 若课程仍引用其 `teacher_id`）：
  ```sql
  UPDATE sys_user SET password = '$2a$10$....' WHERE username IN ('demo_teacher','demo_student');
  ```

> `wenjin.seed.enabled` 必须保持 `false`（默认即 false），避免启动时播种演示数据。

### 2.3.1 角色模型与运维开通教师 / 管理员

公开注册 **只能** 创建学生账号；教师与管理员不得通过 `/api/register` 自助开通。

#### 角色模型

| 角色 | 编码 | 开通方式 |
| --- | --- | --- |
| 教师 | `1` | 首个 ADMIN 就绪后：`PATCH /api/system-admin/users/{id}/role`；或运维审计 SQL（备份路径） |
| 学生 | `2` | 公开注册 `/api/register`（服务端强制） |
| 管理员 | `3` | **一次性 bootstrap** 创建首个 ADMIN；其后一般不再引导；SQL 仍可作为灾备备份 |

客户端提交的遗留 JSON 字段 `role` 在兼容窗口内会被服务端忽略，**不得**用于提权。

#### A. 一次性 bootstrap 创建首个管理员（推荐）

应用提供受控引导：`AdminBootstrapRunner` 仅在 `wenjin.bootstrap.admin.enabled=true` 时加载。仅当库中 **尚无任何 ADMIN** 时，用环境变量中的用户名/密码创建一个 BCrypt 管理员；已有 ADMIN 时启动 fail-fast。

| 配置 / 环境变量 | 含义 |
| --- | --- |
| `wenjin.bootstrap.admin.enabled` | 开关；**默认必须为 false**。仅创建首个 ADMIN 的那一次启动设为 `true` |
| `WENJIN_BOOTSTRAP_ADMIN_USERNAME` → `wenjin.bootstrap.admin.username` | 首个管理员登录名（非空） |
| `WENJIN_BOOTSTRAP_ADMIN_PASSWORD` → `wenjin.bootstrap.admin.password` | 明文初始口令，**长度 ≥ 16**；仅经环境变量注入，勿写入仓库 |

**一键流程（one-shot）——严格按序：**

1. **启用引导**（仅单实例、变更窗口）：
   ```bash
   export WENJIN_BOOTSTRAP_ADMIN_ENABLED=true   # 若配置绑定此 env；或在启动参数中设 wenjin.bootstrap.admin.enabled=true
   export WENJIN_BOOTSTRAP_ADMIN_USERNAME='ops_admin'
   export WENJIN_BOOTSTRAP_ADMIN_PASSWORD='替换为≥16字符的强随机口令'
   ```
   在 `application.yml` / 启动参数中对应：
   ```yaml
   wenjin:
     bootstrap:
       admin:
         enabled: true   # 仅本窗口
         username: ${WENJIN_BOOTSTRAP_ADMIN_USERNAME:}
         password: ${WENJIN_BOOTSTRAP_ADMIN_PASSWORD:}
   ```
2. **单实例启动**后端一次；确认日志出现 `Bootstrap admin created, id=...`（**日志不含密码**），且可用该账号登录拿到 ADMIN 令牌。
3. **立即关闭开关并移除密码环境变量**，再重启为常态配置：
   ```bash
   unset WENJIN_BOOTSTRAP_ADMIN_PASSWORD
   unset WENJIN_BOOTSTRAP_ADMIN_USERNAME
   # wenjin.bootstrap.admin.enabled 必须改回 false（或不设置；组件仅在 havingValue=true 时加载）
   sudo systemctl restart wenjin   # 或你的进程管理方式
   ```

**硬性要求：永远不要在生产长期保持 bootstrap 启用。** 开关常开会把「创建管理员」能力暴露在每次部署启动路径上；口令 env 残留则等同于明文管理凭据。

若用户名已被占用（非 ADMIN 账号占用 `uk_username`），引导会以明确错误拒绝，而不是抛出原始数据库唯一键异常——请换未占用用户名，或走 SQL 备份路径提升已有账号。

#### B. ADMIN 提升教师 / 学生（bootstrap 之后）

首个 ADMIN 登录后，用其令牌调用：

```http
PATCH /api/system-admin/users/{id}/role
Authorization: Bearer <ADMIN_TOKEN>
Content-Type: application/json

{"role": 1}
```

- `role` **仅允许** `1`（TEACHER）或 `2`（STUDENT）；不能通过此接口授予 ADMIN。
- 需要先有目标用户（例如学生先走 `/api/register`，再被提升为教师）。
- 服务端禁止将**最后一个启用管理员**降级，且角色变更在事务内对目标行 `SELECT ... FOR UPDATE`，避免并发降级把 ADMIN 清零。
- 非 ADMIN 令牌访问该路由 → 403。

示例：

```bash
BASE=https://你的域名
ADMIN_TOK=...   # 首个管理员登录得到的 token
# 将 userId=42 的学生提升为教师
curl -s -X PATCH "$BASE/api/system-admin/users/42/role" \
  -H "Authorization: Bearer $ADMIN_TOK" \
  -H 'Content-Type: application/json' \
  -d '{"role":1}'
```

#### C. 运维审计 SQL（备份路径，仍有效）

bootstrap 不可用、灾备恢复、或需在无应用的情况下建号时，仍可用经审计的 SQL。密码必须为 **BCrypt** 哈希（与应用 `BCryptPasswordEncoder` 一致），禁止写入明文。

##### 1. 生成 BCrypt 哈希

任选其一（示例密码请替换为强随机口令）：

```powershell
# 方式 A：Spring Boot 依赖已在本机时，可用 jshell / 小工具调用 BCryptPasswordEncoder
# 方式 B：使用 htpasswd（若可用）
# htpasswd -nbBC 10 "" 'YourStrongPasswordHere' | tr -d ':\n' | sed 's/^//'
```

Java 片段（本地一次性运行，勿把明文密码提交到仓库）：

```java
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class Gen {
  public static void main(String[] args) {
    System.out.println(new BCryptPasswordEncoder().encode(args[0]));
  }
}
```

哈希应形如 `$2a$10$...` 或 `$2b$10$...`。

##### 2. 审计 SQL 插入教师 / 管理员

在变更窗口执行，并保留工单号 / 执行人 / 时间：

```sql
-- 审计：创建教师账号（role=1）
-- ticket: <CHANGE-ID>  operator: <name>  at: <UTC timestamp>
INSERT INTO `sys_user` (`username`, `password`, `real_name`, `role`, `status`)
VALUES (
  'teacher_zhang',
  '$2a$10$REPLACE_WITH_BCRYPT_HASH',  -- 必须是 BCrypt，禁止明文
  '张老师',
  1,   -- TEACHER
  1    -- active
);
```

管理员（`role=3`）同理，仅在有明确授权、且无法走 bootstrap 时创建：

```sql
INSERT INTO `sys_user` (`username`, `password`, `real_name`, `role`, `status`)
VALUES (
  'admin_ops',
  '$2a$10$REPLACE_WITH_BCRYPT_HASH',
  '运维管理员',
  3,   -- ADMIN
  1
);
```

将已有学生提升为教师（备份路径，等价于 ADMIN API）：

```sql
-- 审计：学生 → 教师
UPDATE `sys_user` SET `role` = 1 WHERE `id` = <user_id> AND `status` = 1;
```

##### 3. 校验

```sql
SELECT id, username, real_name, role, status, LEFT(password, 4) AS pwd_prefix
FROM sys_user
WHERE username IN ('teacher_zhang', 'admin_ops');
-- 期望：role 为 1 或 3；pwd_prefix 为 $2a$ / $2b$ / $2y$
```

用该账号登录一次，确认令牌与教师端拦截器工作正常。成功后通过安全渠道将初始口令交付本人，并要求首次登录后尽快修改（当前若尚无自助改密，则由运维再次用 BCrypt 更新 `password` 列）。

#### 禁止事项（角色与账号）

- **禁止**在生产长期开启 `wenjin.bootstrap.admin.enabled`，或把 `WENJIN_BOOTSTRAP_ADMIN_PASSWORD` 留在 systemd/unit/环境文件中。
- 禁止通过公开注册或手工改库把学生账号批量改为教师而无工单。
- 禁止在生产使用 `data.sql` 演示口令。
- 禁止将 BCrypt 生成过程中的明文密码写入 git、聊天记录或工单附件。

### 2.4 日志降噪

**现状**：`application.yml` 为 `com.wenjin: debug`，且 MyBatis-Plus `log-impl: StdOutImpl` 会打印**全部 SQL**——生产环境性能受损且可能泄露数据。在 `application-prod.yml` 覆盖（见第 4 节完整示例）：

```yaml
logging:
  level:
    root: warn
    com.wenjin: info
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.nologging.NoLoggingImpl
```

### 2.5 AI Key

出题 / 标注 / 课程标准抽取依赖大模型（OpenAI 兼容）。不配 Key 时这些功能不可用（其余功能正常）：

```bash
export WENJIN_AI_API_KEY="文本模型 Key"          # 默认 DeepSeek（deepseek-v4-pro）
export WENJIN_AI_VISION_API_KEY="视觉模型 Key"    # 默认 GLM（glm-5v-turbo），图片抽取用
# 可选覆盖：WENJIN_AI_BASE_URL / WENJIN_AI_MODEL / WENJIN_AI_VISION_BASE_URL / WENJIN_AI_VISION_MODEL
```

> Key 仅经环境变量注入，**切勿写进仓库或配置文件**。

### 2.6 前端生产构建 + nginx

`vite` 是开发服务器，**不可用于生产**。构建静态产物：

```bash
cd wenjin-web
npm ci
npm run build        # 产出 dist/
```

把 `dist/` 交给 nginx（配置见第 5.6 节）。

---

## 3. 建议清单（非阻断，但生产应有）

- **HTTPS（强烈建议）**：令牌存于浏览器 `localStorage`，无 HTTPS 易被窃听 / 中间人。用 Let's Encrypt（certbot）签证书，nginx 终止 TLS。
- **健康检查（actuator）**：已引入 `spring-boot-starter-actuator`，且仅暴露 `/actuator/health` 供负载均衡 / 探活（配置见第 4 节）。该端点不在 `/api/**` 下，不受现有鉴权拦截器影响。
- **令牌撤销 / 轮换**：当前令牌签发后 7 天内无法主动失效（无黑名单 / 版本号）。若有「强制下线 / 改密即失效」需求，需加一层（如随用户 `tokenVersion` 校验）——属增量功能，可推迟。
- **数据库迁移与备份**：生产 schema **只走 Flyway**（`db/migration`，migrator 账号 + `WENJIN_FLYWAY_ENABLED=true` 作业）。`schema.sql` 仅本地空库重建，**禁止**对生产执行。配置定期 `mysqldump`；迁移前必备份（见 2.3）。**V9 关系完整性为独立发布**；其后 **V10** 增加 `agent_run` / `agent_step`（M5，可与应用同窗或紧随 V9 后单独 migrate）。审计与回滚见 [project-hardening-rollout.md](docs/runbooks/project-hardening-rollout.md)。
- **限流 / 防滥用**：登录端点与 AI 调用端点可在 nginx 或应用层加限流。

---

## 4. 已落地的 A 层加固配置

| 动作 | 文件 | 说明 | 量 |
|------|------|------|----|
| 已新增 | `wenjin-server/src/main/resources/application-prod.yml` | 生产覆盖：日志、SQL 关闭、CORS、actuator | 小 |
| 已配置 | `wenjin-server/.../config/WebConfig.java` | CORS 来源外部化 + 测试 | 小 |
| 已引入 | `wenjin-server/pom.xml` | `spring-boot-starter-actuator` | 极小 |
| 运维 | Flyway migrator 作业 + app DML 账号 | 见 2.3；不跑 `data.sql` / 不跑生产 `schema.sql` | 中 |
| 已配置 | `application.yml` → `spring.task.scheduling` | 调度线程池 size=2，避免长任务饿死 cleanup/草稿清理 | 小 |
| 已配置 | `wenjin.features.agent-trace-enabled` | M5 Agent 轨迹埋点 + 教师查询 API | 小 |
| 已配置 | `wenjin.limits.trace` / `wenjin.limits.tools` | step I/O 与工具结果截断 | 小 |
| 已迁移 | Flyway `V10__agent_trace.sql` | `agent_run` / `agent_step` 表 | 中 |

### 4.1 配置清单（调度与基线）

| 配置项 | 默认（基线 `application.yml`） | 说明 |
|--------|--------------------------------|------|
| `spring.task.scheduling.pool.size` | `2` | Spring 默认单线程调度器；扩到 2 使 `AiJobWorker.tick` 内联长任务不饿死 `AiJobPayloadCleanup` / `GraphDraftStore` |
| `spring.task.scheduling.thread-name-prefix` | `wj-sched-` | 调度线程名前缀，便于日志与线程 dump 定位 |

```yaml
spring:
  task:
    scheduling:
      pool:
        size: 2
      thread-name-prefix: wj-sched-
```

**决策**：worker 独立 executor 方案暂不采用（当前任务量单飞足够），未来吞吐需求出现时再迁。生产/开发均沿用同一 `spring.task.scheduling` 基线，无需在 `application-prod.yml` 再覆盖；若未来拆独立 executor，优先只改代码与本表，不在 prod 分叉默认值。

### 4.2 M5 Agent 轨迹（feature / limits / V10）

| 配置项 | 默认（dev 基线） | 生产 | 说明 |
|--------|------------------|------|------|
| `wenjin.features.agent-trace-enabled` | `true` | `${WENJIN_FEATURE_AGENT_TRACE:false}` | 关闭时埋点 no-op，教师 `GET /api/teacher/agent-runs**` 统一 404 |
| `wenjin.limits.trace.max-io-chars` | `8000` | 继承基线 | `agent_step.input_json` / `output_json` 超长截断并追加 `[truncated]` |
| `wenjin.limits.tools.max-result-chars` | `4000` | 继承基线 | 通用工具结果字符上限（与伴侣侧 `wenjin.companion.tools.max-result-chars` 并存；后者偏对话上下文） |
| `wenjin.companion.tools.max-result-chars` | `2000` | 继承基线 | 伴侣 ToolGovernor 结果截断（M4） |

```yaml
wenjin:
  features:
    agent-trace-enabled: true   # prod: ${WENJIN_FEATURE_AGENT_TRACE:false}
  limits:
    trace:
      max-io-chars: 8000
    tools:
      max-result-chars: 4000
```

**决策笔记**：

- **默认关生产开关**：轨迹含工具 I/O 摘要，生产需显式 `WENJIN_FEATURE_AGENT_TRACE=true`，与固定诊断 / 图谱对账 / AI job 同一 feature-flag 风格。
- **截断而非全文落库**：`max-io-chars` / `max-result-chars` 防止 MEDIUMTEXT 膨胀与日志噪声；教师页 steps 明细展示的即截断后内容。
- **学生端不开放 trace API**：仅教师/管理员经 `TeacherAgentRunController` 读本课 run；学生令牌应 401/403。
- **Schema**：空库走 Flyway V1…**V10**；既有库在 V9 成功后 migrate **V10**（`CREATE TABLE agent_run` / `agent_step`）。`schema.sql` 仅本地空库快照，生产禁用。
- **发布顺序**：migrate V10 → 滚动应用（可先 flag off）→ 冒烟教师页 `/teacher/agent-runs` → 再开 `WENJIN_FEATURE_AGENT_TRACE`。回滚优先关 flag，不必立刻 drop 表。

**`application-prod.yml` 参考全文**（profile 名即文件名后缀，`SPRING_PROFILES_ACTIVE=prod` 时自动合并覆盖 `application.yml`）：

```yaml
# 裸机/nginx 部署默认仅监听本机；容器部署需显式设 SERVER_ADDRESS=0.0.0.0
server:
  address: ${SERVER_ADDRESS:127.0.0.1}

# 仅写需要覆盖 application.yml 的项；其余沿用基线配置
logging:
  level:
    root: warn
    com.wenjin: info

mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.nologging.NoLoggingImpl   # 生产关闭 SQL 打印

wenjin:
  cors:
    allowed-origin-patterns: ${WENJIN_CORS_ORIGINS:https://你的前端域名}

# Actuator 仅暴露无详情的健康检查
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never
```

上述加固已经落地；发布时只需注入生产环境变量并按本文验证。

---

## 5. 端到端部署步骤

### 5.1 服务器准备
- JDK 21、MySQL 8.0.16+、nginx；后端 8080 仅监听 `127.0.0.1`，对外只放 nginx 80/443。8.0.16 是 CHECK 约束实际强制执行的最低版本，CI 使用 MySQL 8.4。

### 5.2 建库与迁移（生产：不导演示数据，禁止 schema.sql）
```bash
# 1) 创建空库 + migrator/app 账号（见 2.3）——不要 mysql < schema.sql
# 2) 备份（若库中已有数据）— 每次 migrate 前必做，失败可 restore
# 3) 迁移作业（migrator + WENJIN_FLYWAY_ENABLED=true）
export DB_USER=wenjin_migrator
export DB_PASSWORD='替换为 migrator 强密码'
# 先只落到 V8（expand），绝不能在该阶段捎带 V9
SPRING_FLYWAY_TARGET=8 WENJIN_FLYWAY_ENABLED=true java -jar wenjin-server.jar --spring.main.web-application-type=none
# 4) V9 前：执行两份 audit；bad_count 全 0，inventory/review 结果留档
# 5) 独立窗口只落 V9（contract）
SPRING_FLYWAY_TARGET=9 WENJIN_FLYWAY_ENABLED=true java -jar wenjin-server.jar --spring.main.web-application-type=none
# 6) V9 后：mysql ... < ops/sql/post-migration-verification.sql
# 7) V10 Agent 轨迹表（M5；可与应用同窗或独立 migrate）
SPRING_FLYWAY_TARGET=10 WENJIN_FLYWAY_ENABLED=true java -jar wenjin-server.jar --spring.main.web-application-type=none
# 8) 开通真实教师/管理员：见 2.3.1
# 分阶段顺序与 flag 开关：docs/runbooks/project-hardening-rollout.md
# Agent 轨迹生产开关：WENJIN_FEATURE_AGENT_TRACE（见 4.2）
```

上述 non-web 迁移命令仍由一次性作业编排器负责在成功后终止/回收；不得作为持有 DDL 凭据的常驻应用运行。

### 5.3 配置环境变量（systemd 示例，见 5.5）
至少：`SPRING_PROFILES_ACTIVE=prod`、`WENJIN_AUTH_SECRET`、`DB_USER`（app 账号）、`DB_PASSWORD`、`WENJIN_AI_API_KEY`、`WENJIN_AI_VISION_API_KEY`、`WENJIN_CORS_ORIGINS`。

常驻进程**不要**设置 `WENJIN_FLYWAY_ENABLED=true`。

### 5.4 打包后端
```bash
cd wenjin-server
./mvnw -q -DskipTests package      # 产出 target/wenjin-server.jar
```

### 5.5 以 systemd 托管后端
`/etc/systemd/system/wenjin.service`：
```ini
[Unit]
Description=Wenjin Backend
After=network.target mysql.service

[Service]
User=wenjin
Environment=SPRING_PROFILES_ACTIVE=prod
Environment=WENJIN_AUTH_SECRET=替换为强随机值
Environment=DB_USER=wenjin
Environment=DB_PASSWORD=替换为强密码
# 不要设置 WENJIN_FLYWAY_ENABLED=true（迁移用独立作业）
Environment=WENJIN_AI_API_KEY=替换
Environment=WENJIN_AI_VISION_API_KEY=替换
Environment=WENJIN_CORS_ORIGINS=https://你的域名
ExecStart=/usr/bin/java -jar /opt/wenjin/wenjin-server.jar
SuccessExitStatus=143
Restart=on-failure

[Install]
WantedBy=multi-user.target
```
```bash
sudo systemctl daemon-reload && sudo systemctl enable --now wenjin
```

### 5.6 前端 + nginx
```bash
cd wenjin-web && npm ci && npm run build      # → dist/
sudo cp -r dist/* /var/www/wenjin/
```
nginx server 块：
```nginx
server {
    listen 443 ssl;
    server_name 你的域名;
    ssl_certificate     /etc/letsencrypt/live/你的域名/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/你的域名/privkey.pem;

    root /var/www/wenjin;
    index index.html;

    # Vue history 路由：找不到文件回退到 index.html
    location / { try_files $uri $uri/ /index.html; }

    # 反代后端；同源，故无需 CORS
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # SSE（AI 伴侣流式）：关闭缓冲、拉长超时
        proxy_buffering off;
        proxy_read_timeout 600s;
    }
}
# 80 → 443 跳转
server { listen 80; server_name 你的域名; return 301 https://$host$request_uri; }
```
> `/api/companion/chat` 是 SSE 流式接口，nginx 必须 `proxy_buffering off`，否则前端收不到逐字输出。

### 5.7 导入种子图谱/题库（可选，仅演示课需要）
见 README「快速开始」第 4 步（生产正式课程通常由教师端建图，不导演示种子）。

---

## 6. 部署后冒烟验证

后端就绪后，用令牌闭环 6 项验证认证生效（把 `https://你的域名` 换成实际地址）：

```bash
BASE=https://你的域名
# 1) 登录拿令牌（用真实账号）
TOK=$(curl -s -X POST $BASE/api/login -H 'Content-Type: application/json' \
  -d '{"username":"真实用户","password":"真实密码"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
# 2) 带令牌访问本人学情 → code:0
curl -s -H "Authorization: Bearer $TOK" "$BASE/api/companion/conversations?studentId=本人id&courseId=x"
# 3) 无令牌 → code:401   4) 伪造令牌 → code:401   5) 跨用户 → code:403
curl -s "$BASE/api/companion/conversations?studentId=本人id&courseId=x"
curl -s -H "Authorization: Bearer forged.token.value" "$BASE/api/companion/conversations?studentId=本人id&courseId=x"
# 6) 学生令牌访问教师路由 → code:403
curl -s -H "Authorization: Bearer $TOK" "$BASE/api/teacher/courses"
```

额外确认：
- [ ] 浏览器访问走 HTTPS、无证书告警
- [ ] 后端日志已无逐条 SQL、无 debug 噪声
- [ ] `WENJIN_AUTH_SECRET` 已非默认值（启动日志无「使用默认开发密钥」WARN）
- [ ] 演示弱口令账号 `demo_teacher/demo_student` 已删除或改强密
- [ ] 首个 ADMIN 已用 one-shot bootstrap（或 SQL 备份）创建；`wenjin.bootstrap.admin.enabled` 已关闭且密码 env 已移除（见 2.3.1）
- [ ] 教师由 ADMIN `PATCH /api/system-admin/users/{id}/role`（或 SQL 备份）开通，非公开注册
- [ ] AI 伴侣流式对话能逐字输出（SSE 反代生效）

### 6.1 AI job payload topology constraint

AI job upload payloads are stored in the local directory configured by
`wenjin.jobs.payload-dir`. The current deployment topology supports exactly one
Spring Boot application process. Submission and cleanup are coordinated inside
that process so cleanup cannot remove a payload before its job transaction
commits.

Do not run multiple application replicas while file-backed AI jobs are enabled.
Horizontal scaling requires shared object storage plus distributed lifecycle
coordination before enabling `WENJIN_FEATURE_AI_JOB_DISPATCH` on more than one
process.

---

## 7. 环境变量速查表

| 变量 | 用途 | 默认 | 生产建议 |
|------|------|------|----------|
| `SPRING_PROFILES_ACTIVE` | 激活 profile | （无） | **`prod`（必设）** |
| `SERVER_ADDRESS` | 后端监听地址（prod profile） | `127.0.0.1` | 裸机保持默认；容器内显式设 `0.0.0.0` 并仅通过受控 Service/Ingress 暴露 |
| `WENJIN_AUTH_SECRET` | 令牌签名密钥 | `dev-secret-change-me-in-prod`（公开） | **强随机值（必设）** |
| `DB_USER` / `DB_PASSWORD` | 数据库账号 | `root` / `root` | 专用账号 + 强密码 |
| `WENJIN_CORS_ORIGINS` | 允许的前端来源 | `*` | 前端域名（或同源不开 CORS） |
| `WENJIN_AI_API_KEY` | 文本模型 Key | 空 | 真实 Key |
| `WENJIN_AI_VISION_API_KEY` | 视觉模型 Key | 空 | 真实 Key（用图片抽取时） |
| `WENJIN_AI_BASE_URL` / `WENJIN_AI_MODEL` | 文本模型地址/型号 | DeepSeek / deepseek-v4-pro | 按所用服务 |
| `WENJIN_AI_VISION_BASE_URL` / `WENJIN_AI_VISION_MODEL` | 视觉模型地址/型号 | GLM / glm-5v-turbo | 按所用服务 |
| `wenjin.auth.ttl-seconds` | 令牌有效期(秒) | 604800（7天） | 按需 |
| `wenjin.seed.enabled` | 演示数据播种 | false | **保持 false** |
| `wenjin.bootstrap.admin.enabled` | 一次性首个 ADMIN 引导 | false（未设则组件不加载） | **仅建号启动一次为 true，随后立即 false** |
| `WENJIN_BOOTSTRAP_ADMIN_USERNAME` | 引导管理员用户名 | 空 | 仅 one-shot 窗口设置 |
| `WENJIN_BOOTSTRAP_ADMIN_PASSWORD` | 引导管理员明文口令（≥16） | 空 | 仅 one-shot；成功后**立即 unset** |
| `WENJIN_FEATURE_AGENT_TRACE` | M5 Agent 轨迹埋点与教师查询 | prod 默认 false | 需先 migrate V10；开启后教师可访问 `/api/teacher/agent-runs` |

---

## 8. 小结

- **代码/配置加固（A 层）**：仓库侧已落地；按第 2、4 节核对生产环境变量、账号和基础设施配置。
- **运维上线（B 层）**：照第 5 节走，快慢取决于你的服务器/域名/证书是否就绪。
- **角色开通**：公开注册仅学生（`role=2`）；首个 ADMIN 用 one-shot bootstrap（见 2.3.1），教师用 `PATCH /api/system-admin/users/{id}/role`；SQL 审计插入仍是有效备份。
- **加固分阶段上线**：前端 non-2xx → wave-1 后端 → V3–V8 expand → flags-off smoke → sessionToken FE → graph reconcile / fixed diagnostic flags → **V9 独立 contract** → AI jobs；详见 [docs/runbooks/project-hardening-rollout.md](docs/runbooks/project-hardening-rollout.md)。
- 最容易翻车也最致命的一项：**`SPRING_PROFILES_ACTIVE=prod` 与 `WENJIN_AUTH_SECRET` 必须同时设**——这是认证安全的总开关。
