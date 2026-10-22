# 问津

知识图谱驱动的个性化学习导航系统。当前演示课程为「软件工程」。

问津的核心叙事是把学习做成一套可闭环的导航系统：先定位学生当前掌握状态，再诊断薄弱根因，随后生成个性化学习路径，并在练习、遗忘、复习和 AI 伴侣对话中持续更新证据流。

## 当前进度

当前代码已完成 **基线平台 + M1–M11 全部主链路**：学生端学习闭环（诊断/练习/复习/路径）、AI 伴侣（工具调用/长期记忆/RAG/反思探针/学习计划）、教师端（建图/题库/抽取/看板/轨迹/周报）与生产部署骨架。M6 评测已能执行真实编排（REPLAY 回放 + LIVE/RECORD）；剩余欠债为用真实模型录制 golden 与 Judge 校准（见 M6 章节）。

| 里程碑 | 状态 | 已落地能力 |
| --- | --- | --- |
| 基线平台 | 已完成 | 多课程、登录注册、HMAC Bearer 令牌、教师/学生权限、知识图谱导入、染色地图、诊断、路径、成长档案、教师工作台 |
| M1 练习闭环 | 已完成 | 节点练习会话、服务端判分、答题证据写入、EWMA 掌握度更新、路径步骤自动通过、必要时路径重算 |
| M2 图引导自适应诊断 | 已完成 | DAG 传播推断、贪心信息最大化选点、有限题量预算、探针式诊断、实测/推断节点分类、固定 25 题诊断保留 |
| M3 遗忘衰减与复习调度 | 已完成 | 读时指数衰减、raw/effective 双分值、今日复习队列、复习直通 M1 自由练习、ColorMap 褪色态 |
| M4 伴侣工具调用 | 已完成 | 伴侣两段式工具调用、工具治理（白名单/预算/超时/缓存/截断）、SSE 工具事件、对话内练习卡 |
| M5 Agent 轨迹 | 已完成 | `agent_run`/`agent_step` 埋点、工具结果截断与 I/O 截断、教师端轨迹列表/steps 明细、特性开关与 Flyway V10 |
| M6 伴侣评测 | 真评测已接通 | 场景 JSON S01–S22（含 `liveFaults`；S22 复盘错题，persona 可带 `mistakes`）、真实编排 REPLAY（golden v2）、LIVE/RECORD、指标与 Judge 阈值判定、报告对比与校准工具、CI `eval-replay` + 手动 `eval-live`；golden 仍为合成，待有 Key 后 RECORD 替换；S18/S20/S21 待 LIVE golden |
| M7 长期记忆 | 已完成 | EPISODIC/SEMANTIC/PROCEDURAL 三层记忆、Jaccard 去重、异步蒸馏、`recall_student_context` / `record_observation` 工具、Flyway V12 |
| M8 RAG | 已完成 | ngram FULLTEXT 检索、NODE_REF 语料入库、RETRIEVAL 槽注入、`search_materials` 工具、引用校验、Flyway V13 |
| M9 反思与降级 | 已完成 | 理解探针（`probe_understanding`）、换策略重讲（StrategySwitcher）、零 LLM 降级（kill-switch 矩阵）、phase-1 意图路由 |
| M10 学习计划 | 已完成 | 确定性 PlanAlgorithm（due/weak/frontier 打包）、DRAFT→确认 HITL、每日 re-plan、daily-brief、run 级取消、Flyway V14 |
| M11 多智能体 | 已完成（默认关） | 教师周报（确定性聚合 + 可选 LLM 叙述）、Supervisor 质量抽查（复用 M6 Judge）、Flyway V15 |

近期进展：伴侣新增理解探针卡、资料引用与每日简报；教师端新增语料审核与周报页面；V5–V9 加固迁移（并发护栏/题面指纹/引用完整性契约）已合入 main。

## 产品能力

### 学生端

- 染色地图：按知识图谱展示每个节点的掌握状态，支持缩放、拖拽、缩略图导航、搜索、章节筛选、薄弱根因回溯和今日复习入口。
- 入口诊断：保留完整固定卷，也提供图引导的自适应快速诊断。
- 诊断结果：输出节点掌握分类、薄弱前置原因和可解释结论。
- 学习路径：根据前置关系和掌握度生成拓扑有序路径，练习后动态更新。
- 节点练习：针对单个知识点生成练习会话，提交后由服务端判分并写入掌握度证据。
- 今日复习：由 M3 衰减模型找出“曾经掌握但正在遗忘”的节点，并一键进入复习练习。
- AI 学习伴侣：SSE 流式对话，支持工具胶囊、节点详情/掌握度/复习队列查询、对话内练习卡、理解探针卡与资料引用。
- 学习计划与每日简报：AI 起草备考计划（DRAFT→确认 HITL），每日 re-plan 并生成每日简报（M10）。
- 主动陪伴：伴侣空会话 opener（时段问候 + 最多 2 张动作卡：错题复盘 / 到期复习 / 计划任务 / 薄弱节点 / 学习前沿）与事件型提示条（错题爆发 / 知识遗忘 / 计划落后，带冷却与每日上限、可关闭），导航小红点。确定性读时聚合，零 LLM；`proactive-enabled` 关闭则接口 404、前端回退固定话术。
- 成长档案：展示掌握度快照和学习变化。

### 教师端

- 课程管理：创建课程、删除课程、发布/下架课程。
- 图谱审核工作台：节点和边的增删改、待审核边处理、星图预览。
- 课程标准抽取审核：从图片、PDF、Excel 等材料中抽取图谱草稿，审核后全量替换生效，并记录召回率等指标。
- 题目审核池：AI 出题、存量题标注、JSON/Excel 题库导入、批量审核。
- 学情看板：按课程聚合学生掌握度和薄弱分布。
- Agent 轨迹：按课程分页查看伴侣等 Agent run，展开 steps 查看工具/LLM 调用与截断后的 I/O。
- 语料审核：上传课程资料（NODE_REF），审核 chunk↔知识点映射（M8）。
- 周报：确定性学情周报与质量抽查报告（M11，默认关闭）。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 前端 | Vue 3、Vite 5、Pinia、Vue Router、ECharts（按需引入）、axios、原生 fetch SSE、手写 SVG 染色地图 |
| 后端 | Spring Boot 3.3.5、Java 21（虚拟线程用于 SSE 与工具超时）、MyBatis-Plus、MySQL 8.0.16+、Flyway（默认关闭） |
| AI | OpenAI 兼容 `/chat/completions`，文本模型默认 DeepSeek，视觉模型默认 GLM，可用环境变量替换 |
| 文档解析 | Apache POI、PDFBox |
| 安全 | 自研 HMAC Bearer 令牌（常量时间比对）、BCrypt 密码、教师/管理员拦截器、学生资源归属校验（404 防枚举） |
| 测试 | JUnit 5、Mockito、Spring Boot Test、Testcontainers MySQL、Vitest + V8 coverage（80/70 门禁）、Playwright + axe、bundle 预算门禁 |

## 目录结构

```text
Fordo/
├─ wenjin-server/                      # Spring Boot 后端
│  ├─ src/main/java/com/wenjin/
│  │  ├─ controller/                   # 学生端、教师端、管理端 API
│  │  ├─ service/                      # 诊断、练习、路径、复习、伴侣、作业等业务
│  │  │  ├─ companion/                 # M4 伴侣工具与治理管道（14 个白名单工具）
│  │  │  ├─ trace/                     # M5 Agent 执行轨迹埋点
│  │  │  ├─ memory/                    # M7 长期记忆蒸馏/去重/注入
│  │  │  ├─ rag/                       # M8 检索与语料入库
│  │  │  ├─ plan/                      # M10 计划、re-plan、取消护栏
│  │  │  └─ multiagent/                # M11 周报与抽查
│  │  ├─ eval/                         # M6 评测：scenario/golden/hard/judge/replay/fixture
│  │  ├─ support/                      # 纯算法支撑：判分、AdaptiveEngine、PlanAlgorithm、PromptAssembler 等
│  │  ├─ security/                     # HMAC 令牌、密钥守卫、课程归属校验
│  │  └─ config/                       # 拦截器、当前用户、AI/工具/特性开关配置
│  ├─ src/main/resources/
│  │  ├─ db/migration/                 # Flyway 版本化迁移 V1–V15（护栏、记忆、RAG、计划、周报）
│  │  ├─ schema.sql                    # 仅本地空库重建快照（会删数据，生产禁用）
│  │  ├─ data.sql                      # 本地演示账号和课程
│  │  └─ application*.yml              # 开发/生产配置
│  ├─ src/test/                        # 单元/集成测试（Testcontainers + M6 评测场景）
│  └─ seed/                            # 软件工程图谱与题库种子
├─ wenjin-web/                         # Vue 前端
│  ├─ src/views/                       # 学生端与教师端页面
│  ├─ src/components/                  # 练习卡、复习面板、顶部导航等
│  ├─ src/api/                         # API 封装，companion.js 负责 SSE
│  ├─ src/store、composables、utils/   # Pinia store、latest-request 竞态门、图谱布局/渲染
│  ├─ test/                            # 前端纯逻辑/模板回归测试
│  └─ e2e/                             # 真机验收脚本（Playwright + *.mjs）
├─ ops/sql/                            # 生产 SQL：迁移预检、完整性审计、ngram 预检等
├─ docs/                               # 质量基线、发布 runbook、规划文档
├─ .github/workflows/ci.yml            # CI：后端 verify / eval-replay / 前端 check / Playwright
├─ DEPLOYMENT.md                       # 生产部署与加固指南
└─ 问津_软件工程图谱_v0.3.{json,xlsx}   # 演示图谱导出件（教师审阅用）
```

## 数据与种子

- **生产 / CI schema 演进**：`wenjin-server/src/main/resources/db/migration/`（Flyway，共 15 个迁移）。
  - `V1__baseline_schema.sql`：空库建全部 20 张表（`CREATE TABLE IF NOT EXISTS`，无 DROP / 种子）。
  - `V2__legacy_milestones.sql`：标记 m01/m02 已并入 V1（`SELECT 1;`）；既有库 `baseline-version: 2` 后从 V3 起。
  - `V3–V9`（加固）：固定诊断冻结、软归档、路径并发护栏、题面 SHA-256 指纹、AI 作业表、伴侣摘要列、关系完整性契约（物理 FK + SIGNAL 预检）。
  - `V10–V15`（功能）：Agent 轨迹、评测、长期记忆、RAG、学习计划、多智能体周报。
  - 应用默认 `spring.flyway.enabled=false`（`WENJIN_FLYWAY_ENABLED`）；发布流水线用 migrator 凭据单独跑迁移（expand V3–V8 与 contract V9 分窗口部署，`SPRING_FLYWAY_TARGET` 封顶），成功后再启普通应用实例。
- `schema.sql`：仅本地空库重建快照（含 `DROP TABLE`，**会删数据**），与 V1 最终 DDL 同步；**生产禁用**。
- 软件工程演示图谱：`wenjin-server/seed/问津_软件工程图谱_v0.3.json`，42 个节点，53 条边。
- 软件工程演示题库：`wenjin-server/seed/问津_软件工程题库_v0.1.json`，25 道种子题。
- 本地演示账号来自 `data.sql`：
  - 教师：`demo_teacher / demo`
  - 学生：`demo_student / demo`
  - 演示课程：`软件工程`，课程编码 `52015CC4B4`，课程 ID 默认 `1`

### 迁移集成测试

需要本机 Docker（Testcontainers 拉 `mysql:8.4`）。CI 必须执行，不可因本机无 Docker 而跳过。

```powershell
Set-Location wenjin-server
.\mvnw.cmd -q -Dtest=SchemaMigrationIT test
```

## 快速开始

### 1. 环境要求

- JDK 21
- Node.js 18+ 或 20+
- MySQL 8.0.16+（CI / Testcontainers 使用 8.4）
- 集成测试另需 Docker
- Windows PowerShell，或能等价执行下列命令的终端

### 2. 初始化数据库

**本地空库（可重复、会删数据）**：

```powershell
mysql -u root -p < wenjin-server/src/main/resources/schema.sql
mysql -u root -p wenjin < wenjin-server/src/main/resources/data.sql
```

**等价空库路径（受控迁移）**：创建空库 `wenjin` 后，用具备 DDL 的 migrator 账号启动一次：

```powershell
$env:WENJIN_FLYWAY_ENABLED = "true"
# DB_USER / DB_PASSWORD 指向 migrator，然后 spring-boot:run 或专用 migrate 作业
```

### 3. 配置环境变量

AI Key 只放环境变量，不要写入仓库。

```powershell
$env:DB_USER = "root"
$env:DB_PASSWORD = "root"

# 文本模型：出题、标注、伴侣、M4 工具调用终答
$env:WENJIN_AI_API_KEY = "your-text-model-key"

# 视觉模型：图片课程标准抽取需要
$env:WENJIN_AI_VISION_API_KEY = "your-vision-model-key"

# 本地快速开始必须显式启用 dev；无 profile 也会按安全模式 fail-fast
$env:SPRING_PROFILES_ACTIVE = "dev"
# 仅显式 dev/test 允许开发密钥；生产环境严禁使用此值
$env:WENJIN_AUTH_SECRET = "local-dev-secret-change-me"
```

`AuthSecretGuard` 仅在激活的 profile 非空且全部为 `dev`/`test` 时放宽密钥检查。
无 profile、`prod` 或混合 profile 都会拒绝默认值、空值及 UTF-8 长度不足 32 字节的密钥；
生产环境必须显式启用 `prod` 并配置强随机、至少 32 UTF-8 字节的独立密钥。

常用可选项：

```powershell
$env:WENJIN_AI_BASE_URL = "https://api.deepseek.com"
$env:WENJIN_AI_MODEL = "deepseek-v4-pro"
$env:WENJIN_AI_VISION_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
$env:WENJIN_AI_VISION_MODEL = "glm-5v-turbo"
```

### 4. 启动后端

```powershell
cd wenjin-server
.\mvnw.cmd spring-boot:run
```

如果本机 `spring-boot:run` 遇到插件或类加载问题，可以改用打包运行：

```powershell
cd wenjin-server
.\mvnw.cmd package -DskipTests
java -jar target\wenjin-server.jar
```

后端默认端口：`http://localhost:8080`。

### 5. 导入演示图谱和题库

后端启动后，在项目根目录执行：

```powershell
curl.exe -X POST "http://localhost:8080/api/admin/graph/import?courseCode=52015CC4B4" `
  -H "Content-Type: application/json" `
  --data-binary "@wenjin-server/seed/问津_软件工程图谱_v0.3.json"

curl.exe -X POST "http://localhost:8080/api/admin/question/import/json?courseId=1" `
  -F "file=@wenjin-server/seed/问津_软件工程题库_v0.1.json;type=application/json"
```

### 6. 启动前端

```powershell
cd wenjin-web
npm install
npm run dev
```

前端默认端口：`http://localhost:5173`，Vite 会把 `/api` 代理到后端 `8080`。

## 核心 API

所有业务接口统一前缀为 `/api`，常规返回体为 `Result<T>`：`{ code, message, data }`。

| 模块 | 路径 | 用途 |
| --- | --- | --- |
| 登录注册 | `/api/login`、`/api/register` | 用户身份与令牌（公开注册固定为学生角色） |
| 课程 | `/api/course` | 可选课程、我的课程、选课 |
| 图谱查询 | `/api/graph/{courseId}` | ColorMap 所需节点、边、掌握度 |
| 固定诊断 | `/api/diagnostic` | 固定卷组卷、提交、结果 |
| 自适应诊断 | `/api/diagnostic/adaptive` | M2 探针式诊断 |
| 路径 | `/api/path` | 路径生成、当前路径、步骤完成 |
| 练习 | `/api/practice` | M1 节点练习开始、提交、历史 |
| 复习 | `/api/review/today` | M3 今日复习队列 |
| AI 伴侣 | `/api/companion` | SSE 对话、会话列表、会话删除 |
| 主动陪伴 | `/api/companion/opener`、`/api/companion/hints/count`、`/api/companion/hints/dismiss` | opener（问候+动作卡+提示条）、提示条数量（小红点，只读）、关闭提示；`proactive-enabled` 关则 404 |
| 理解探针 | `/api/companion/probe/{sessionId}/submit` | M9 探针作答（EWMA 隔离，不写掌握度） |
| run 取消 | `/api/companion/runs/{id}/cancel` | M10 编排转向/取消 |
| 学习计划 | `/api/student/plans/*`、`/api/student/daily-brief` | M10 计划确认/取消/当前计划/每日简报 |
| 资料引用 | `/api/student/chunks` | 伴侣引用胶囊抽屉（M8） |
| 图谱管理 | `/api/admin/graph` | JSON/Excel 导入、课程标准抽取与提交 |
| 题目管理 | `/api/admin/question` | AI 出题、标注、JSON/Excel 题库导入 |
| 教师课程 | `/api/teacher/courses` | 建课、删课、发布/下架 |
| 教师图谱 | `/api/teacher/graph` | 图谱审核与编辑 |
| 教师题目 | `/api/teacher/questions` | 题目审核池 |
| 教师看板 | `/api/teacher/dashboard` | 学情聚合 |
| 教师 Agent 轨迹 | `/api/teacher/agent-runs` | M5 run 列表与 steps 明细（教师/管理员） |
| 教师语料 | `/api/teacher/rag` | M8 语料与 chunk↔节点映射审核 |
| 教师周报 | `/api/teacher/reports` | M11 周报/抽查报告（`multi-agent-enabled` 开启后） |
| 管理用户 | `/api/system-admin/users` | 角色开通/变更（仅 ADMIN） |

## M4 伴侣工具调用

M4 把 AI 伴侣从“读取上下文并回答”升级为“按白名单工具真实查数据、真实发起练习”。实现要点：

- `CompanionAiClient` 保留原流式接口，并新增非流式 `completeWithTools` 与完整消息流式 `streamMessages`。
- `CompanionServiceImpl` 采用两段式编排：非流式工具解析循环，再用累积消息流式终答。
- `ToolGovernor` 负责白名单、JSON 参数校验、预算、超时、结果截断和审计日志。
- `query_mastery` 查询学生当前有效掌握度，包含 M3 衰减语义。
- `get_node_detail` 查询节点元数据、前置节点和后继节点。
- `start_practice` 通过 nodeCode 或 nodeName 发起 M1 自由练习，默认 2 题，夹紧到 1-3 题，并通过 SSE 推送 `practice_card`。
- 前端 `Companion.vue` 渲染 `tool_start`、`tool_result` 和 `practice_card`，内嵌 `QuestionAnswerCard` 完成作答提交。

> 工具集随里程碑扩展至 **14 个**：M7 增加 `recall_student_context` / `record_observation`；M8 增加 `search_materials`；M9 增加 `probe_understanding`；M10 增加 `draft_study_plan` / `get_plan_progress`；错因辅导增加 `get_recent_mistakes`；另有 `get_review_queue` / `get_learning_frontier` / `trace_prerequisites` / `schedule_review`。详见各里程碑章节。

相关代码：

- `wenjin-server/src/main/java/com/wenjin/service/companion/`
- `wenjin-server/src/main/java/com/wenjin/service/CompanionServiceImpl.java`
- `wenjin-server/src/main/java/com/wenjin/controller/CompanionController.java`
- `wenjin-web/src/views/Companion.vue`
- `wenjin-web/src/api/companion.js`

## M5 Agent 执行轨迹

M5 为伴侣等 Agent 编排补齐可观测轨迹，并给教师提供按课程查询的只读页面。

### 后端埋点与工具改造

- Flyway **`V10__agent_trace.sql`** 新增 `agent_run` / `agent_step`（本地 `schema.sql` 已同步）。
- `AgentTraceService` + `TraceContext`：run 开始/结束、step 写入；I/O 按 `wenjin.limits.trace.max-io-chars` 截断并标记 `[truncated]`。
- 伴侣工具结果按 `wenjin.limits.tools.max-result-chars` / 伴侣侧 `wenjin.companion.tools.max-result-chars` 夹紧，避免超大 payload 进轨迹与上下文。
- 特性开关 **`wenjin.features.agent-trace-enabled`**：开发默认 `true`；生产默认 `false`，经 `WENJIN_FEATURE_AGENT_TRACE` 开启。关闭时教师轨迹接口统一 404，埋点降级为 no-op。

### 教师端页面

- 路由：`/teacher/agent-runs`（`TeacherAgentTrace.vue`），导航「Agent 轨迹」。
- 列表：按当前教师课程分页展示 kind / status / model / tokens / duration / stepCount。
- 明细：点击 run 行加载 `GET /api/teacher/agent-runs/{runId}/steps`，可展开查看截断后的 input/output JSON。
- 鉴权：仅教师/管理员；学生令牌访问 API → 401/403。

相关代码：

- `wenjin-server/src/main/resources/db/migration/V10__agent_trace.sql`
- `wenjin-server/src/main/java/com/wenjin/service/trace/`
- `wenjin-server/src/main/java/com/wenjin/controller/TeacherAgentRunController.java`
- `wenjin-web/src/views/TeacherAgentTrace.vue`
- `wenjin-web/src/api/teacher.js`（`fetchAgentRuns` / `fetchAgentRunSteps`）

生产开关与限流配置见 **`DEPLOYMENT.md`**（`agent-trace-enabled`、`limits.trace` / `limits.tools`、调度池、V10）。

## M6 伴侣评测（REPLAY / LIVE / RECORD）

M6 用场景脚本 + 硬断言 + LLM-as-judge 回归伴侣行为。三种模式共用同一套执行链：**夹具建世界 → 套 persona → 真实 `CompanionServiceImpl`/`CompanionOrchestrator`/工具 → 读回 `agent_run`/`agent_step` → 硬断言 + 指标**，区别只在 LLM 从哪来。

| 模式 | LLM 来源 | 入口 | 何时跑 |
| --- | --- | --- | --- |
| REPLAY | golden v2 逐轮回放（`ReplayCompanionAiClient.streamTurn`） | `ReplayScenarioIT` | CI `eval-replay`（Docker/Testcontainers）；本地指向 `wenjin_eval` |
| LIVE | 真实模型（`wenjin.ai.*`） | `LiveEvalSuiteIT` | 手动 / CI `eval-live`（`workflow_dispatch`，secret `WENJIN_AI_API_KEY`） |
| RECORD | LIVE + 录制每轮 `streamTurn` 输出为 golden v2 | `LiveEvalSuiteIT -Dwenjin.eval.record=true` | 有 Key 时替换合成 golden |

| 构件 | 位置 |
| --- | --- |
| 场景（事实源） | `wenjin-server/src/test/resources/eval/scenarios/S01.json` … `S21.json`（可选 `liveFaults`） |
| Golden v2 | `wenjin-server/src/test/resources/eval/golden/{code}.json`（20 份，缺 S18；当前均为 `synthetic:true` 手写合成） |
| 执行链 | `com.wenjin.eval.runner`（`EvalHarness`、`ServiceCompanionTurnRunner`、`LiveEvalSuite`、`EvalScenarioEnvironment`）、`fixture`（`EvalWorldBuilder` 建隔离课程 `EVAL_SE`）、`replay`、`golden`、`hard`、`judge`、`metrics`、`report`、`calibration` |
| 报告 | REPLAY：`target/eval-report.md`；LIVE：`target/eval-live/<yyyyMMdd-HHmmss>/report.md`、`report.json`（判 judge 时另有 `calibration.csv`） |

- **ENABLED**（18 个）：S01–S17、S19；**PENDING**：S18（跨会话记忆，无 golden）、S20/S21（学习计划；显式列出时可跑）。
- **Golden v2**：`{"scenario","format":2,"recordedAt","model","synthetic","turns":[{"rounds":[{"content","toolCalls":[{"name","arguments"}],"finishReason"}]}]}`，一个 turn 对应脚本一句，一个 round 对应一次 `streamTurn`。回放严格：轮次用尽或有剩余轮次都记为 `replay` 硬失败并提示 **need re-record**（说明编排行为已变）。v1（phase1/phase2）格式已废弃。
- **`liveFaults`**（真模型无法自然复现的场景）：`{"llmFailures":N}` 让前 N 次 LLM 调用按供应商失败抛出（S07 → 降级）；`{"maxToolCalls":N}` 临时覆盖工具预算（S06 → 预算耗尽 GUARD）。REPLAY/LIVE/RECORD 同样生效。
- **判定**：硬断言 7 项 + `execution`/`replay` 两项执行检查；Overall = 硬断言通过且 Judge 未判 FAIL（Judge 缺失记 N/A）。指标：TTFT、总耗时、LLM 轮数、工具次数、真实 usage tokens、按单价估成本（`wenjin.eval.price.*`）。
- **数据库隔离**：评测会重置学生状态、建课，**只能用独立库**（默认 `wenjin_eval`）或 Testcontainers；JDBC URL 库名为 `wenjin` 时 `EvalDatabaseGuard` 直接拒跑。Schema 由 Flyway V1…最新 建立，**不要**用 `schema.sql` 灌评测库（它含 `USE wenjin` + DROP）。

本地准备（一次）：

```powershell
mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS wenjin_eval DEFAULT CHARACTER SET utf8mb4"
cd wenjin-server
$env:WENJIN_AUTH_SECRET = "ci-test-secret-not-for-prod-at-least-32-bytes"
```

REPLAY（真实编排回归，约 1 分钟；`-Dwenjin.eval.jdbc-url` 省略时走 Testcontainers，需要 Docker）：

```powershell
$env:WENJIN_EVAL_IT = "true"
.\mvnw.cmd test "-Dtest=com.wenjin.eval.**" "-Dwenjin.eval.jdbc-url=jdbc:mysql://127.0.0.1:3306/wenjin_eval"
```

（`-Dtest=com.wenjin.eval.**` 同时跑评测单测；只跑回放用 `-Dtest=ReplayScenarioIT`。不设 `WENJIN_EVAL_IT` 时 IT 自动跳过。URL 不必带 `&` 参数，缺省会补 `useAffectedRows/characterEncoding=UTF-8/serverTimezone/useSSL/allowPublicKeyRetrieval`。）

LIVE（需要 Key；默认每场景 3 次）：

```powershell
$env:WENJIN_EVAL_LIVE = "true"
$env:WENJIN_AI_API_KEY = "<your key>"
.\mvnw.cmd test "-Dtest=LiveEvalSuiteIT" "-Dwenjin.eval.jdbc-url=jdbc:mysql://127.0.0.1:3306/wenjin_eval" "-Dwenjin.eval.repeat=3" "-Dwenjin.eval.judge=true" "-Dwenjin.eval.scenarios=S01,S04"
```

RECORD（把真实输出写回 golden，`synthetic:false`；每场景保留首个硬断言通过的那次）：加 `"-Dwenjin.eval.record=true"`（可选 `"-Dwenjin.eval.record-dir=..."`，默认 `src/test/resources/eval/golden`），跑完 `git diff` 审阅再提交。

对比两次报告 / Judge 校准：

```powershell
.\mvnw.cmd test "-Dtest=EvalToolsTest" "-Dwenjin.eval.compare=target/eval-live/<A>/report.json,target/eval-live/<B>/report.json"   # → target/eval-compare.md
# 在 calibration.csv 的 human_score 列填人工分（1–5）后：
.\mvnw.cmd test "-Dtest=EvalToolsTest" "-Dwenjin.eval.calibration-csv=target/eval-live/<B>/calibration.csv"   # 一致率（|人−机|≤1）与各维度偏差
```

无 Key 时 `LiveEvalPipelineIT` 用按 golden 脚本作答的桩模型 + 假 Judge 走完整 LIVE 链路（夹具、故障注入、RECORD、Judge、聚合、报告），保证接上 Key 只差模型本身。

## M7 长期记忆（三层 + PromptAssembler）

M7 为学生伴侣增加三层记忆（EPISODIC / SEMANTIC / PROCEDURAL）与分槽上下文组装。

### 能力摘要

- Flyway **`V12__student_memory.sql`**：`student_memory` 表 + 会话 `distilled_through_message_id` / `summary_origin`。
- **PromptAssembler** 槽序：`SYSTEM → SKILL → SUMMARY → MEMORY → RETRIEVAL → RECENT`；预算 `wenjin.limits.prompt.*`。
- **MEMORY 槽注入**：按 effective top-k（`inject-top-k`，默认 5）分组渲染「学生画像 / 近期学习经历 / 有效策略」，注入后强化选中项。
- **异步蒸馏**：`MEMORY_DISTILL` / `SUMMARY_REFRESH`（摘要折叠后入队；flag 关时不派发 distill）。
- **工具**：`recall_student_context`（读、可缓存）、`record_observation`（写，每 run ≤ `write-per-run`）。
- 特性开关 **`wenjin.features.memory-enabled`**：开发默认 `true`；生产默认 `false`（`WENJIN_FEATURE_MEMORY`）。关闭时：不注入 MEMORY、不入队 distill、记忆工具从 schema 下架。

相关代码：

- `wenjin-server/src/main/resources/db/migration/V12__student_memory.sql`
- `wenjin-server/src/main/java/com/wenjin/support/PromptAssembler.java`
- `wenjin-server/src/main/java/com/wenjin/service/memory/`
- `wenjin-server/src/main/java/com/wenjin/service/companion/RecallStudentContextTool.java`
- `wenjin-server/src/main/java/com/wenjin/service/companion/RecordObservationTool.java`

配置明细见 **`DEPLOYMENT.md`**（`memory-enabled`、`limits.memory.*`、`limits.prompt.*`、V12）。

## M8 RAG（语料挂载图谱）

M8 将课程资料切块挂载知识点，经 FULLTEXT（ngram）检索注入 `RETRIEVAL` 槽，并提供 `search_materials` 工具与引用校验。

### 能力摘要

- Flyway **`V13__rag.sql`**：`doc_source` / `doc_chunk`（FULLTEXT ngram）/ `chunk_node_map`。
- **NODE_REF 入库**：`DOC_INGEST` 作业解析 Markdown frontmatter + `##` 切块，映射直写 `APPROVED`（样例见 `src/test/resources/rag/node-ref/`）。
- **检索**：`RetrievalService` 两段式（节点圈定 → 可选全课程放宽）；`FulltextRetriever` 实现 `RetrieverPort`（向量实现预留）。
- **注入**：`rag-enabled` 且有命中时填充 RETRIEVAL 不可信包裹块。
- **引用校验（R4，端到端）**：系统提示静态段「资料引用」要求句末标注 `资料 #<编号>`（编号只能来自本轮注入片段或 `search_materials` 返回的 `chunkId`，未覆盖须说「课程资料未覆盖」）。本 turn 可引用集合 = 注入的 chunkId ∪ `search_materials` 结果 chunkId（`CitationGuard`，工具路径与 legacy 路径共用）。turn 结束对最终文本提取 `资料 #N`，经 `CitationValidator` 校验：
  - SSE 新事件 `citations` `{valid:[…], invalid:[…]}`，仅在回复含引用时发送，顺序在最后一个 `token`（及 `practice_card`）之后、`probe_card`/`done` 之前；
  - **持久化文本**中的无效引用替换为 `（引用无效）`（已流式下发的原文不可撤回，前端据 `citations` 把无效编号划掉、仅 valid 渲染为可点芯片；历史消息无事件，维持原样）；
  - trace 记一步 `step_type=CITATION`、`name=citation_check`，`output={"valid":[…],"invalid":[…]}`（不计入工具预算）；
  - `rag-enabled=false` 时整条链关闭：不发事件、不改写文本、不记 step；
  - 评测：硬断言 `citationsValid`（trace 的 citation_check 步 `invalid` 必须为空）、judge 维度 `citation_faithfulness`；golden 里用 `{{cite:<资料标题>}}`/`{{cite:N}}` 占位符引用 `search_materials` 实际返回的 chunk（chunkId 由数据库分配）。
- **工具**：`search_materials`（读、可缓存；`query`≤50、`nodeCodes`≤5、`topK` 1..5）。
- 特性开关 **`wenjin.features.rag-enabled`**：开发默认 `true`；生产默认 `false`（`WENJIN_FEATURE_RAG`）。关闭时：空 RETRIEVAL、工具从 schema 下架。
- **部署**：上线前执行 `ops/sql/preflight-ngram.sql`，确认 `ngram_token_size=2`。版权：整本出版教材勿部署到公网演示环境。

相关代码：

- `wenjin-server/src/main/resources/db/migration/V13__rag.sql`
- `wenjin-server/src/main/java/com/wenjin/support/rag/`
- `wenjin-server/src/main/java/com/wenjin/service/rag/`
- `wenjin-server/src/main/java/com/wenjin/service/companion/SearchMaterialsTool.java`
- `wenjin-server/src/main/java/com/wenjin/service/companion/CitationGuard.java`

配置明细见 **`DEPLOYMENT.md`**（`rag-enabled`、`limits.rag.*`、V13、ngram 预检）。

## M9 反思与降级（probe 探针 + kill-switch 矩阵）

M9 为伴侣补齐「确认听懂」与「紧急熔断」两个回路。

### 能力摘要

- **理解探针**：`probe_understanding` 工具发起单题探针（EWMA 隔离，不写掌握度），经 SSE 推送 `probe_card`；作答未通过时可「换个方式再讲」。
- **换策略真实生效**：探针提交结果记入进程内 `ProbeOutcomeStore`（不落库，重启即回到默认讲法）；下一 turn 若焦点节点或最近探针节点有 30 分钟内未通过的探针，服务端按 `StrategySwitcher` 顺序（类比→举例→分步→对比→图谱）选下一个讲法，注入 system prompt 动态段「换种讲法」，同一次失败只切换一次；该 turn 视为讲解并再次自动探针。上一讲法无效 / 改用后通过分别写入程序性记忆 `[STRATEGY][无效|有效]`。
- **错因辅导**：`get_recent_mistakes`（只读、可缓存；参数 `nodeCode?`、`practiceSessionId?`、`limit?` 1–5 默认 3）读取近期客观题错题（单选/多选/判断，排除简答），返回所选 vs 正确选项及所选干扰项 `point_node_code` 指向的误区节点与 `topMisconceptions`；system prompt「错因辅导」规则要求先查错题、针对误区讲清为什么会选错、不直接念答案。本 turn 若该工具给出误区节点，自动探针改为探第一误区节点。
- **复盘错题入口**：`POST /api/companion/chat` 请求体可选 `reviewPracticeSessionId`（正整数）；控制器校验该练习会话属于当前学生与课程（否则 404），仅本 turn 有效，作为 `get_recent_mistakes` 的默认会话并在 prompt 中提示先复盘。
- **phase-1 意图路由**：伴侣第一阶段决策输出 `intent ∈ {EXPLAIN, QUERY, PRACTICE, CHAT}`（缺省 CHAT）；EXPLAIN 后可自动发起探针；练习意图命中时短路到 `start_practice`。
- **零 LLM 降级**：`companion-llm-enabled=false` 或运行时 LLM 连续失败 → `CompanionDegradeService` 模板话术 + 练习卡（due → weak → frontier），run 记 `DEGRADED`。
- **kill-switch 矩阵**：

| 开关 | 关闭时的行为 |
| --- | --- |
| `companion-llm-enabled` | 零 LLM 降级模板 + 练习卡；`agent_run.status=DEGRADED` |
| `reflection-enabled` | 不下发 `probe_understanding`、不自动探针（含误区定向探针）、不注入「换种讲法」 |
| `memory-enabled` | MEMORY 槽为空、记忆工具下架、不派发蒸馏 |
| `rag-enabled` | RETRIEVAL 槽为空、`search_materials` 下架 |
| `plan-enabled` | 计划工具下架、re-plan 不跑、brief 隐藏计划段 |

相关代码：

- `wenjin-server/src/main/java/com/wenjin/service/companion/ReflectionCoordinator.java`
- `wenjin-server/src/main/java/com/wenjin/service/companion/StrategySwitcher.java`
- `wenjin-server/src/main/java/com/wenjin/service/companion/CompanionDegradeService.java`
- `wenjin-server/src/main/java/com/wenjin/support/Phase1DecisionParser.java`

配置明细见 **`DEPLOYMENT.md`**（4.2.1 紧急 kill switch 矩阵）。

## M10 学习计划（确定性算法 + HITL）

M10 提供考试备考计划：确定性任务序列（LLM 零参与 packing）、DRAFT→确认 HITL、每日 re-plan、读时 daily-brief、run 级取消。

### 能力摘要

- Flyway **`V14__agent_plan.sql`**：`agent_plan` / `agent_plan_task` / `tool_effect_key`。
- **PlanAlgorithm**（纯函数）：候选 due &gt; weak &gt; frontier；每日 `daily-task-cap`；target 日总复习；溢出 `PLAN_SCOPE_TOO_LARGE` + 建议节点集。计划可解释、零幻觉——任务由算法生成，LLM 仅可选解释文案（失败则空）。
- **工具**：`draft_study_plan`（危档，只产 DRAFT）、`get_plan_progress`（读档，缓存白名单）。
- **学生端**：`POST /api/student/plans/{id}/confirm|cancel`、`GET /api/student/plans/current`、`GET /api/student/daily-brief`。
- **DRAFT 红线**：不进 daily-brief、不被 re-plan 触碰、不产生任务提醒。
- **re-plan**：`@Scheduled` cron `wenjin.plan.replan-cron`（默认 04:30），仅 ACTIVE，失败单计划隔离。
- **取消**：`agent_run.cancel_requested_at` + `POST /api/companion/runs/{id}/cancel`；编排 step 边界检查。
- 特性开关 **`wenjin.features.plan-enabled`**：开发默认 `true`；生产默认 `false`（`WENJIN_FEATURE_PLAN`）。

相关代码：

- `wenjin-server/src/main/resources/db/migration/V14__agent_plan.sql`
- `wenjin-server/src/main/java/com/wenjin/support/plan/PlanAlgorithm.java`
- `wenjin-server/src/main/java/com/wenjin/service/plan/`
- `wenjin-server/src/main/java/com/wenjin/service/companion/DraftStudyPlanTool.java`
- `wenjin-server/src/main/java/com/wenjin/service/companion/GetPlanProgressTool.java`

配置明细见 **`DEPLOYMENT.md`**（`plan-enabled`、`limits.plan.*`、`wenjin.plan.replan-cron`、V14）。

## M11 多智能体（可选 · 默认关闭）

M11 在同一编排骨架上挂三个 **persona**（不引入 LangGraph/AutoGen）：

1. **学生伴学 Agent**（M4–M10）：对话态、工具集、记忆/RAG/计划；轨迹见 M5 `agent_run`。
2. **TeacherInsightAgent（教师周报）**：只读聚合掌握度/练习/对话统计 → 结构化 payload（弱点、异常、参与度环比）+ 可选 LLM 叙述；**不读 `student_memory`**。
3. **SupervisorSampler（质量抽查）**：每周随机抽 N 条 `COMPANION_CHAT`（排除 DEGRADED/CANCELLED），复用 M6 `JudgeService` 打分，低分写入 `flags` 并可跳转既有教师 trace。

后两者为一次性任务型 agent（无对话态、无记忆写入），由 `AiJob` 异步执行；toggle 关时与 M10 基线一致。

### 能力摘要

- Flyway **`V15__agent_report.sql`**：`agent_report`（`uk_report_period` 幂等）。
- 确定性聚合 **`ReportAggregations`**（可单测）：班级最低 5 节点、7 日 effective 下降 &gt;0.15、14 日无练习、周对话/练习环比。
- Job kind：`TEACHER_WEEKLY_REPORT` / `SUPERVISOR_SAMPLE`；叙事失败仍 READY。
- API：`GET /api/teacher/reports?courseId=&kind=`、`GET /api/teacher/reports/{id}`（`assertManageCourse`）。
- 特性开关 **`wenjin.features.multi-agent-enabled`**：**默认 `false`**（开发与生产一致）；`WENJIN_FEATURE_MULTI_AGENT`。关闭 → 零 cron、worker 拒跑、报告 API 404。

相关代码：

- `wenjin-server/src/main/resources/db/migration/V15__agent_report.sql`
- `wenjin-server/src/main/java/com/wenjin/support/multiagent/`
- `wenjin-server/src/main/java/com/wenjin/service/multiagent/`
- `wenjin-server/src/main/java/com/wenjin/controller/TeacherReportController.java`

配置明细见 **`DEPLOYMENT.md`**（`multi-agent-enabled`、`limits.supervisor.*`、`wenjin.multi-agent.report-cron`、V15）。

## 测试与验收

后端测试：

```powershell
cd wenjin-server
.\mvnw.cmd clean verify
```

前端测试与构建：

```powershell
cd wenjin-web
npm run test:coverage
npm run check
```

真机验收脚本位于 `wenjin-web/e2e/`。这些脚本通常需要后端、前端、MySQL 和系统 Edge 已经可用；M4 验收还需要可用的文本模型 Key；M5 需要 V10 表与 `agent-trace-enabled=true`。

```powershell
cd wenjin-web
node .\e2e\m1-practice-loop.mjs
node .\e2e\m2-adaptive-diag.mjs
node .\e2e\m4-companion-tools.mjs
node .\e2e\m5-agent-trace.mjs
```

无后端时可用脚本合约测试（不启动浏览器）：

```powershell
cd wenjin-web
npm run test -- test/m4-e2e-script-contract.test.js
npm run test -- test/m5-e2e-script-contract.test.js
```

当前仓库后端测试文件 171 个、`@Test` 用例约 1200 处（测试代码与主代码行数比约 0.94:1）；前端包含 Vitest 单元/覆盖率门禁（store/composables/api/utils 行 80% / 分支 70%）与 Playwright 验收脚本（CI 门禁为 accessibility + mobile-map 两条 spec，其余 `e2e/*.mjs` 验收脚本需后端就绪后人工触发）。

## 生产部署注意

生产部署请先阅读 `DEPLOYMENT.md`。最关键的底线：

- 必须启用 `prod` profile，并设置强随机 `WENJIN_AUTH_SECRET`。
- 不要导入 `data.sql` 中的演示弱口令账号。
- 不要使用 `root/root` 数据库账号。
- 不要把 AI Key 写入配置文件或提交到仓库。
- 推荐由 nginx 托管前端静态文件，并反向代理 `/api` 到后端，只对外暴露 HTTPS。
- `application-prod.yml` 已关闭 SQL 打印并收紧日志；CORS 生产域名通过 `WENJIN_CORS_ORIGINS` 配置。
- **单进程拓扑约束**：AI 作业 payload 存本地磁盘、生命周期靠进程内锁协调，当前仅支持单应用实例；水平扩展前需先实现共享对象存储 + 分布式生命周期协调（DEPLOYMENT.md §6.1）。

## 后续建议

- **评测补全**：S18（跨会话记忆）与 S20/S21（学习计划）录制 LIVE golden 后转 ENABLED；用真实 LIVE 数据完成 Judge 校准（人机一致率 ≥80%），并把 threshold 判罚落到报告口径。
- **可靠性**：把 `ProbeSessionStore` / `ReviewScheduleStore`（当前为进程内存态，重启丢失）持久化；多实例部署前完成 AiJob payload 共享存储与分布式协调。
- **上线检查**：生产开启任一 feature 前确认对应迁移已跑（V10 轨迹、V12 记忆、V13 RAG（先执行 `ops/sql/preflight-ngram.sql`）、V14 计划、V15 周报）；M4/M5 真机验收（工具预算/降级、教师轨迹学生越权 403）纳入发布清单。
- **前端**：把依赖后端的 `e2e/*.mjs` 验收脚本逐步并入 CI；继续拆分 ColorMap / Companion / TeacherGraphReview 等大文件。
- **文档同步**：场景清单、API 表等随里程碑更新（本文件为 M11 截止的现状）。
