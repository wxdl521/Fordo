# 问津

知识图谱驱动的个性化学习导航系统。当前演示课程为「软件工程」。

问津的核心叙事是把学习做成一套可闭环的导航系统：先定位学生当前掌握状态，再诊断薄弱根因，随后生成个性化学习路径，并在练习、遗忘、复习和 AI 伴侣对话中持续更新证据流。

## 当前进度

当前代码已经完成 M1-M4 主链路，并包含教师端建图、题库审核、课程标准抽取、课程发布、认证与生产配置骨架。

| 里程碑 | 状态 | 已落地能力 |
| --- | --- | --- |
| 基线平台 | 已完成 | 多课程、登录注册、HMAC Bearer 令牌、教师/学生权限、知识图谱导入、染色地图、诊断、路径、成长档案、教师工作台 |
| M1 练习闭环 | 已完成 | 节点练习会话、服务端判分、答题证据写入、EWMA 掌握度更新、路径步骤自动通过、必要时路径重算 |
| M2 图引导自适应诊断 | 已完成 | DAG 传播推断、有限题量预算、探针式诊断、实测/推断节点分类、固定 25 题诊断保留 |
| M3 遗忘衰减与复习调度 | 已完成 | 读时指数衰减、raw/effective 双分值、今日复习队列、复习直通 M1 自由练习、ColorMap 褪色态 |
| M4 伴侣工具调用 | 已完成 | 伴侣两段式工具调用、`query_mastery` / `get_node_detail` / `start_practice`、工具治理、SSE 工具事件、对话内练习卡 |

近期还做过 ColorMap 体验修补：今日复习面板改为地图内侧边抽屉，绘图 loading 不再误挂在 hover 状态上，左下角图例与操作提示不再重叠。

## 产品能力

### 学生端

- 染色地图：按知识图谱展示每个节点的掌握状态，支持缩放、拖拽、缩略图导航、搜索、章节筛选、薄弱根因回溯和今日复习入口。
- 入口诊断：保留完整固定卷，也提供图引导的自适应快速诊断。
- 诊断结果：输出节点掌握分类、薄弱前置原因和可解释结论。
- 学习路径：根据前置关系和掌握度生成拓扑有序路径，练习后动态更新。
- 节点练习：针对单个知识点生成练习会话，提交后由服务端判分并写入掌握度证据。
- 今日复习：由 M3 衰减模型找出“曾经掌握但正在遗忘”的节点，并一键进入复习练习。
- AI 学习伴侣：SSE 流式对话，支持工具胶囊、节点详情查询、真实掌握度查询和对话内练习卡。
- 成长档案：展示掌握度快照和学习变化。

### 教师端

- 课程管理：创建课程、删除课程、发布/下架课程。
- 图谱审核工作台：节点和边的增删改、待审核边处理、星图预览。
- 课程标准抽取审核：从图片、PDF、Excel 等材料中抽取图谱草稿，审核后全量替换生效，并记录召回率等指标。
- 题目审核池：AI 出题、存量题标注、JSON/Excel 题库导入、批量审核。
- 学情看板：按课程聚合学生掌握度和薄弱分布。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 前端 | Vue 3、Vite 5、Pinia、Vue Router、ECharts、axios、原生 fetch SSE |
| 后端 | Spring Boot 3.3.5、Java 21、MyBatis-Plus、MySQL 8、Flyway（默认关闭） |
| AI | OpenAI 兼容 `/chat/completions`，文本模型默认 DeepSeek，视觉模型默认 GLM，可用环境变量替换 |
| 文档解析 | Apache POI、PDFBox |
| 安全 | 自研 HMAC Bearer 令牌、BCrypt 密码、教师拦截器、学生资源归属校验 |
| 测试 | JUnit 5、Mockito、Spring Boot Test、Testcontainers MySQL、Node test、Playwright + 系统 Edge |

## 目录结构

```text
Fordo/
├─ wenjin-server/                    # Spring Boot 后端
│  ├─ src/main/java/com/wenjin/
│  │  ├─ controller/                  # 学生端、教师端、管理端 API
│  │  ├─ service/                     # 诊断、练习、路径、复习、伴侣、抽取等业务
│  │  ├─ service/companion/           # M4 伴侣工具与治理管道
│  │  ├─ support/                     # 判分、自适应引擎、图方向、演示播种等支撑
│  │  ├─ security/                    # HMAC 令牌与生产密钥守卫
│  │  └─ config/                      # 拦截器、当前用户、AI/工具配置
│  ├─ src/main/resources/
│  │  ├─ db/migration/                # Flyway 版本化迁移（V1 baseline + 增量）
│  │  ├─ schema.sql                   # 仅本地空库重建快照（会删数据，生产禁用）
│  │  ├─ data.sql                     # 本地演示账号和课程
│  │  └─ application*.yml             # 开发/生产配置
│  └─ seed/                           # 软件工程图谱与题库种子
├─ wenjin-web/                        # Vue 前端
│  ├─ src/views/                      # 学生端与教师端页面
│  ├─ src/components/                 # 练习卡、复习面板、顶部导航等
│  ├─ src/api/                        # API 封装，companion.js 负责 SSE
│  ├─ test/                           # 前端纯逻辑/模板回归测试
│  └─ e2e/                            # 真机验收脚本
├─ 00-总纲-闭环路线图.md              # M1-M4 总体路线
├─ 01-M1-练习闭环.md
├─ 02-M2-图引导自适应诊断.md
├─ 03-M3-遗忘衰减与复习调度.md
├─ 04-M4-伴侣工具调用.md
├─ M4-GOAL.md                         # M4 最终目标说明
├─ DEPLOYMENT.md                      # 生产部署与加固指南
└─ 测试截图/                           # 阶段验收截图
```

## 数据与种子

- **生产 / CI schema 演进**：`wenjin-server/src/main/resources/db/migration/`（Flyway）。
  - `V1__baseline_schema.sql`：空库建全部 20 张表（`CREATE TABLE IF NOT EXISTS`，无 DROP / 种子）。
  - `V2__legacy_milestones.sql`：标记 m01/m02 已并入 V1（`SELECT 1;`）；既有库 `baseline-version: 2` 后从 V3 起。
  - 应用默认 `spring.flyway.enabled=false`（`WENJIN_FLYWAY_ENABLED`）；发布流水线用 migrator 凭据单独跑迁移，成功后再启普通应用实例。
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
- MySQL 8
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

# 生产必须换成强随机密钥；本地开发也建议显式设置
$env:WENJIN_AUTH_SECRET = "local-dev-secret-change-me"
```

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
| 登录注册 | `/api/login`、`/api/register` | 用户身份与令牌 |
| 课程 | `/api/course` | 可选课程、我的课程、选课 |
| 图谱查询 | `/api/graph/{courseId}` | ColorMap 所需节点、边、掌握度 |
| 固定诊断 | `/api/diagnostic` | 固定卷组卷、提交、结果 |
| 自适应诊断 | `/api/diagnostic/adaptive` | M2 探针式诊断 |
| 路径 | `/api/path` | 路径生成、当前路径、步骤完成 |
| 练习 | `/api/practice` | M1 节点练习开始、提交、历史 |
| 复习 | `/api/review/today` | M3 今日复习队列 |
| AI 伴侣 | `/api/companion` | SSE 对话、会话列表、会话删除 |
| 图谱管理 | `/api/admin/graph` | JSON/Excel 导入、课程标准抽取与提交 |
| 题目管理 | `/api/admin/question` | AI 出题、标注、JSON/Excel 题库导入 |
| 教师课程 | `/api/teacher/courses` | 建课、删课、发布/下架 |
| 教师图谱 | `/api/teacher/graph` | 图谱审核与编辑 |
| 教师题目 | `/api/teacher/questions` | 题目审核池 |
| 教师看板 | `/api/teacher/dashboard` | 学情聚合 |

## M4 伴侣工具调用

M4 把 AI 伴侣从“读取上下文并回答”升级为“按白名单工具真实查数据、真实发起练习”。实现要点：

- `CompanionAiClient` 保留原流式接口，并新增非流式 `completeWithTools` 与完整消息流式 `streamMessages`。
- `CompanionServiceImpl` 采用两段式编排：非流式工具解析循环，再用累积消息流式终答。
- `ToolGovernor` 负责白名单、JSON 参数校验、预算、超时、结果截断和审计日志。
- `query_mastery` 查询学生当前有效掌握度，包含 M3 衰减语义。
- `get_node_detail` 查询节点元数据、前置节点和后继节点。
- `start_practice` 通过 nodeCode 或 nodeName 发起 M1 自由练习，默认 2 题，夹紧到 1-3 题，并通过 SSE 推送 `practice_card`。
- 前端 `Companion.vue` 渲染 `tool_start`、`tool_result` 和 `practice_card`，内嵌 `QuestionAnswerCard` 完成作答提交。

相关代码：

- `wenjin-server/src/main/java/com/wenjin/service/companion/`
- `wenjin-server/src/main/java/com/wenjin/service/CompanionServiceImpl.java`
- `wenjin-server/src/main/java/com/wenjin/controller/CompanionController.java`
- `wenjin-web/src/views/Companion.vue`
- `wenjin-web/src/api/companion.js`

## 测试与验收

后端测试：

```powershell
cd wenjin-server
.\mvnw.cmd test
```

前端测试与构建：

```powershell
cd wenjin-web
node --test
npm run build
```

真机验收脚本位于 `wenjin-web/e2e/`。这些脚本通常需要后端、前端、MySQL 和系统 Edge 已经可用；M4 验收还需要可用的文本模型 Key。

```powershell
cd wenjin-web
node .\e2e\m1-practice-loop.mjs
node .\e2e\m2-adaptive-diag.mjs
node .\e2e\m4-companion-tools.mjs
```

当前仓库中后端测试文件约 60+ 个，`@Test` 用例约 580 个；前端包含 Node test 与多条 Playwright 验收脚本。验收截图集中在 `测试截图/`。

## 生产部署注意

生产部署请先阅读 `DEPLOYMENT.md`。最关键的底线：

- 必须启用 `prod` profile，并设置强随机 `WENJIN_AUTH_SECRET`。
- 不要导入 `data.sql` 中的演示弱口令账号。
- 不要使用 `root/root` 数据库账号。
- 不要把 AI Key 写入配置文件或提交到仓库。
- 推荐由 nginx 托管前端静态文件，并反向代理 `/api` 到后端，只对外暴露 HTTPS。
- `application-prod.yml` 已关闭 SQL 打印并收紧日志；CORS 生产域名通过 `WENJIN_CORS_ORIGINS` 配置。

## 后续建议

- 把 M4 的真机验收纳入发布前检查清单，尤其是工具预算、图谱外节点注入和 `tools.enabled=false` 降级。
- 继续补充课程标准抽取和教师审核的端到端验收脚本。
- 若要上公网，优先完成 `DEPLOYMENT.md` 中的 CORS、账号、密钥和数据库权限加固。
