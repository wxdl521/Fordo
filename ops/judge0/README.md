# Judge0 参考部署（wenjin.code-check.backend=judge0）

仅作参考配置：CI 与应用都**不会**启动它。生产推荐把代码执行放到**独立主机**上的自托管 Judge0，
应用主机就不需要 docker 权限（见 DEPLOYMENT.md「代码执行沙箱」）。

1. 在独立主机/VM 上：`cp judge0.conf.example judge0.conf`，改 `AUTHN_TOKEN`、`REDIS_PASSWORD`、`POSTGRES_PASSWORD`。
2. `docker compose up -d db redis`，约 10 秒后 `docker compose up -d`。
3. `curl -H "X-Auth-Token: <token>" http://127.0.0.1:2358/about` 返回版本即可。
4. 2358 端口只对问津应用主机开放（防火墙 / 内网），不要暴露公网。
5. 问津侧环境变量：
   ```
   WENJIN_CODE_CHECK_BACKEND=judge0
   WENJIN_CODE_CHECK_JUDGE0_URL=http://<judge0-host>:2358
   WENJIN_CODE_CHECK_JUDGE0_TOKEN=<AUTHN_TOKEN>
   WENJIN_FEATURE_CODE_CHECK=true
   ```
   基址与 token 也可在管理后台「系统配置 → 代码沙箱」热改（token 加密存储）。

`language_id` 默认 62 = Java (OpenJDK 13.0.1)，这是 Judge0 CE 语言表里唯一的 Java：**不支持 record、文本块等
JDK 14+ 语法**。部署后先 `curl -H "X-Auth-Token: <token>" http://127.0.0.1:2358/languages` 查看实际语言表，若自建镜像带了
Java 17/21，就把 `wenjin.code-check.judge0.language-id` 改成对应 id。运行时 JVM 参数无法逐次提交指定（`compiler_options`
只给 javac），内存上限由 isolate 按问津发送的 `memory_limit` 强制。
在管理后台修改 Judge0 基址会立即作废已保存的 token（防止把 token 发到新地址），需要重新填写。问津逐用例提交（`wait=true`），不发送期望输出，比对在问津侧完成。
Judge0 的 server/workers 需要 `privileged: true`（isolate 管理 cgroups），这正是建议独立主机的原因。
