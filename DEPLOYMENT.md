# 问津 · 生产部署与加固指南

本文档记录生产部署底线与账号开通流程。公开注册**只能**创建学生账号；教师与管理员不得通过 `/api/register` 自助开通。

## 角色模型

| 角色 | 编码 | 开通方式 |
| --- | --- | --- |
| 教师 | `1` | 运维审计 SQL（下文）或后续 Task 2 的 ADMIN 入口 |
| 学生 | `2` | 公开注册 `/api/register`（服务端强制） |
| 管理员 | `3` | 运维审计 SQL；后续由 ADMIN 受控入口管理 |

客户端提交的遗留 JSON 字段 `role` 在兼容窗口内会被服务端忽略，**不得**用于提权。

## 生产底线

- 必须启用 `prod` profile，并设置强随机 `WENJIN_AUTH_SECRET`。
- **不要**导入 `data.sql` 中的演示弱口令账号（`demo_teacher` / `demo_student`）。
- 不要使用 `root/root` 数据库账号。
- 不要把 AI Key 写入配置文件或提交到仓库。
- 推荐由 nginx 托管前端静态文件，并反向代理 `/api` 到后端，只对外暴露 HTTPS。
- CORS 生产域名通过 `WENJIN_CORS_ORIGINS` 配置。
- 生产 schema 演进只走增量 migration，不要对生产库重跑 `schema.sql`。

## 运维开通教师账号（Task 2 上线前）

在 Task 2 的 ADMIN 自助/受控开通入口上线前，生产教师账号**只能**由运维执行经审计的 SQL 创建，密码必须为 **BCrypt** 哈希（与应用 `BCryptPasswordEncoder` 一致），禁止写入明文。

### 1. 生成 BCrypt 哈希

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

### 2. 审计 SQL 插入教师

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

管理员（`role=3`）同理，仅在有明确授权时创建：

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

### 3. 校验

```sql
SELECT id, username, real_name, role, status, LEFT(password, 4) AS pwd_prefix
FROM sys_user
WHERE username IN ('teacher_zhang', 'admin_ops');
-- 期望：role 为 1 或 3；pwd_prefix 为 $2a$ / $2b$ / $2y$
```

用该账号登录一次，确认令牌与教师端拦截器工作正常。成功后通过安全渠道将初始口令交付本人，并要求首次登录后尽快修改（当前若尚无自助改密，则由运维再次用 BCrypt 更新 `password` 列）。

## 禁止事项

- 禁止通过公开注册或手工改库把学生账号批量改为教师而无工单。
- 禁止在生产使用 `data.sql` 演示口令。
- 禁止将 BCrypt 生成过程中的明文密码写入 git、聊天记录或工单附件。
