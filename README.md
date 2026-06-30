# 问津 · 知识图谱驱动的个性化学习导航系统

> 演示课程：软件工程

**问津**是一个 AI 驱动、任何课程老师都能一键搭建的智能学习导航平台：老师导入课程标准即可生成知识图谱，学生借助它完成学情诊断、获取个性化学习路径与 AI 学习建议。

可类比「学习版的导航软件」——导航要知道「你在哪、要去哪、怎么走」，并在情况变化时实时重新规划；问津对学习过程做同样的事：**定位学情 → 诊断根因 → 规划路径 → 动态调整**。学生面对一门课「不知卡在哪、不知接下来学什么」，正是身处迷津，而系统所做的一切本质上是一件事——**指点迷津**，故名「问津」。

> **当前状态**：六个迭代阶段 + 课程标准抽取、课程发布、令牌认证等里程碑均已落地。后端 **300+ 单元/集成测试全绿**，学生端与教师端全链路真机验收通过。详见 [演进历程](#十一演进历程与进度)。

---

## 一、要解决的三个痛点

1. **学生不知道自己「卡在哪、为什么卡」** —— 做错一道题只知道「这题不会」，却看不到真正薄弱的前置知识点。
2. **学习缺乏个性化路径** —— 所有人按同一套教材同一顺序学，无法按个人掌握情况动态调整「接下来该学什么」。
3. **知识图谱构建门槛高** —— 个性化学习的前提是知识图谱，而梳理知识点依赖关系高度耗费人力，导致同类系统大多停留在单门写死的课程上。

其中第 3 点是同类系统公认最难的环节，问津通过 **「AI 辅助建图谱 + 标准化导入」** 把这一难点转化为产品亮点。

---

## 二、系统架构（四层，数据单向流动并形成闭环）

```
┌─────────────────────────────────────────────┐
│  ① 图谱构建层（教师端）                          │
│     AI 自动建图谱 / 结构化导入 / 可视化微调        │
└───────────────────┬─────────────────────────┘
                    │ 统一数据契约（点 + 边 JSON）
┌───────────────────▼─────────────────────────┐
│  ② 数据与存储层                                 │
│     知识图谱（节点表 + 边表）/ 多课程隔离 / 学情数据 │
└───────────────────┬─────────────────────────┘
                    │
┌───────────────────▼─────────────────────────┐
│  ③ 智能核心层                                   │
│     学生画像 / 认知诊断 / 动态路径 / AI 学习伴侣    │
└───────────────────┬─────────────────────────┘
                    │
┌───────────────────▼─────────────────────────┐
│  ④ 体验呈现层（学生端）                          │
│     知识地图 / 学习路径 / 成长档案 / 资源匹配       │
└─────────────────────────────────────────────┘
```

**统一数据契约**：无论图谱来自 AI 自动生成、结构化文件导入还是手动编辑，最终都收敛为同一套内部结构（节点 + 边）。系统从设计之初即支持多课程（`course_id` 多租户隔离），不为后续扩展返工。

---

## 三、学生端 · 智能核心（项目的灵魂）

按「学生使用流程」组织为四个能力，对应前端四组页面：

### 🧭 学生画像（定位：你在哪）— `ColorMap.vue` 染色地图
- 进入后完成一套覆盖课程主要知识点的**入口诊断小测**（客观题为主，分层抽样组卷，默认 25 题）。
- 为每个知识点计算**掌握度**并分级（已掌握 / 薄弱 / 未学），映射到图谱形成「**染色地图**」，一眼看清薄弱区域；画布支持缩放、平移、整图自适应。
- 掌握度用 **EWMA 近期加权更新**（`新掌握度 = α × 本次表现 + (1−α) × 旧掌握度`，默认 α=0.3），新表现能覆盖旧表现——连续答对几题，地图就能从「薄弱」变绿回「已掌握」。

### 🔍 认知诊断（诊断：你为什么卡）— `Diagnostic.vue` / `DiagnosticResult.vue`
- 不止「对错打分」，而是**反向定位薄弱根因**：答错某点时顺着图谱**前置边逆向回溯（最多 2 层）**，检查前置点掌握情况。
- 主 / 次权重参与判定；多个前置薄弱按「权重 × 掌握度缺口」给出嫌疑根因列表。
- **结论可解释**：如「你 UML 图掌握不好，根本原因是需求建模基础未吃透，建议先回补」。

### 🛤️ 动态自适应路径（导航：接下来学什么）— `LearningPath.vue`
- 在图上做**拓扑排序**（学一个知识点前其前置必须先学），生成有序学习路径。
- **动态自适应**：每次学习 / 答题后实时重算剩余路径——已掌握的不再推荐，把精力导向仍薄弱处。

### 🤖 AI 学习伴侣（陪伴：随时可问）— `Companion.vue`
- 接入大模型，结合学生当前画像与图谱位置进行**对话式答疑**，**SSE 流式**输出。
- **防幻觉护栏**：system prompt 注入本课程知识点白名单与学生画像，回答**限定在本课程图谱范围内**，超纲问题礼貌引导回主题。

> 另有 **成长档案**（`Growth.vue`，掌握度快照曲线回看）、**知识点详情**（`KnowledgePoint.vue`）、**移动端地图**（`MobileMap.vue`）。

---

## 四、教师端 · 图谱构建与审核闭环（role=1）

| 页面 | 路由 | 能力 |
| --- | --- | --- |
| 图谱审核工作台 | `/teacher/graph` | 节点 / 边的增改删、多课程切换、确定性力导向「星图」预览、课程标准抽取入口 |
| 课程标准抽取审核 | `/teacher/graph-extract-review` | **图片 / 文档（PDF/Excel）AI 抽取** → 表格审核改删增 → 节点/边召回率指标 → 全量替换生效 |
| 题目审核池 | `/teacher/questions` | AI 出题即标注、存量题标注、批量审批、一键全通过、全节点批量出题 |
| 学情看板 | `/teacher/dashboard` | 课程整体学情聚合 |
| 课程管理 | （工作台内） | 建课 / 删课（级联）/ **发布 / 下架** |

**三种建图方式殊途同归**：① AI 自动建图谱（核心亮点，节点召回率 / 边准确率量化呈现）；② 结构化文件导入（严格校验：节点 ID 唯一、边端点存在、关系类型合法、前置边环检测）；③ 可视化微调（图上拖拽增删，所见即所得）。

---

## 五、认证与权限

身份模型为 **HMAC 签名 Bearer 令牌 + BCrypt 密码**（详见 `docs/superpowers/specs/2026-06-30-authentication-design.md`）：

- **登录**：`POST /api/login` 校验密码后签发两段式 HMAC-SHA256 令牌（`payloadB64.signatureB64`，载荷含 `uid/role/exp`），返回 `LoginVO { token, user }`。
- **密码**：BCrypt 哈希；演示账号初始为明文，**首次登录时透明升级**为 BCrypt，无需迁移脚本。
- **前端**：登录后令牌存 `localStorage.wj_token`，`http.js` 给所有请求附 `Authorization: Bearer <token>`，SSE（原生 fetch）单独补头；响应 `code===401` 清登录态跳登录页。路由守卫 `role===1` 才放行 `/teacher/*`（**仅体验层**）。
- **后端（安全边界）**：`AuthContextInterceptor` 校验 Bearer 令牌填充 `CurrentUser`（无效/缺失保持匿名）；学生端点用 `AccessGuard.assertSelf` 防越权访问他人学情；`TeacherAuthInterceptor` 回查数据库角色，仅教师可达 `/api/teacher/**`。
- **生产部署必须设置环境变量 `WENJIN_AUTH_SECRET`**：默认密钥 `dev-secret-change-me-in-prod` 公开在仓库，不覆盖则可被伪造令牌。`AuthSecretGuard` 在 `prod` profile 下若仍用默认密钥会 fail-fast 中止启动，其余环境仅告警。

> 统一返回体 `Result<T>`：`{ code, message, data }`，`code=0` 成功；`BusinessException` 经全局异常处理器转为 **HTTP 200 + body code（401 未登录 / 403 无权）**。

---

## 六、技术栈

| 层 | 选型 |
| --- | --- |
| 前端 | Vue 3 + Vite 5 + Pinia + Vue Router + ECharts 5 + axios（端口 5173） |
| 后端 | Spring Boot 3.3.5 + Java 21 + MyBatis-Plus 3.5.7（端口 8080） |
| 数据库 | MySQL 8（节点表 + 边表即表达图谱；utf8mb4） |
| 文档解析 | Apache POI（Excel）+ PDFBox（PDF 文本抽取） |
| 安全 | spring-security-crypto（仅 BCrypt 工具，不引入 Security 过滤链）+ 自研 HMAC 令牌 |
| AI 层 | OpenAI 兼容 `/chat/completions`，文本默认 DeepSeek、视觉默认 GLM（智谱），接口隔离、可插拔 |
| 测试 | JUnit 5 + Mockito + Spring Boot Test（后端）、Playwright + 系统 Edge（前端 e2e） |

无重量级新依赖：令牌方案零额外依赖，BCrypt 仅引 `spring-security-crypto`。

---

## 七、目录结构

```
Fordo/
├── wenjin-server/                          # 后端：Spring Boot 3 + Java 21 + MyBatis-Plus + MySQL 8
│   ├── src/main/java/com/wenjin/
│   │   ├── controller/                     # 13 组接口（见下「接口一览」）
│   │   ├── service/ + service/impl/        # 业务：诊断/掌握度/路径/伴侣/抽取/审核…
│   │   ├── security/                       # TokenService（HMAC 令牌）/ AuthSecretGuard
│   │   ├── config/                         # 拦截器：AuthContext / TeacherAuth / AccessGuard / CurrentUser
│   │   ├── entity/ mapper/ dto/ common/    # 实体 / Mapper / DTO / Result·异常
│   ├── src/main/resources/
│   │   ├── schema.sql                      # 建库建表（18 张表，含演示账号/课程，可重复执行）
│   │   ├── data.sql                        # 演示选课等增量种子
│   │   └── application.yml                 # 端口/DB/AI/认证/诊断/掌握度 配置
│   └── seed/                               # 图谱·题库 JSON 种子 + 各阶段增量迁移 SQL
│       ├── 问津_软件工程图谱_v0.3.json       # 42 节点 / 53 边
│       └── 问津_软件工程题库_v0.1.json       # 入口诊断种子题库（25 题）
└── wenjin-web/                             # 前端：Vue 3 + Vite + Pinia + ECharts 5
    ├── src/views/                          # 13 个页面（学生端 9 + 教师端 4）
    ├── src/api/                            # http.js（Bearer 拦截器）/ companion.js（SSE）等
    ├── src/router/index.js                 # 路由 + 教师端守卫
    └── e2e/                                # Playwright 真机验收脚本
```

---

## 八、数据模型（18 张表）

| 域 | 表 |
| --- | --- |
| 身份与课程 | `sys_user`、`course`、`student_course` |
| 知识图谱 | `kg_node`（节点）、`kg_edge`（边，含关系类型/前置） |
| 题库 | `question`、`question_option`、`question_node`（题—知识点关联） |
| 学情 | `student_mastery`（当前掌握度）、`mastery_snapshot`（历史快照）、`answer_record` |
| 学习路径 | `learning_path`、`learning_path_item` |
| 学习资源 | `resource`、`node_resource` |
| AI 伴侣 | `companion_conversation`、`companion_message` |
| 抽取审核 | `extraction_review`（课程标准抽取记录 + 召回率指标） |

图谱即「节点表 + 边表」，`course_id` 贯穿各表实现多课程隔离。

---

## 九、快速开始

### 0. 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **21**（必需） | 后端 |
| Maven | — | 仓库内置 **Maven Wrapper（`mvnw`）**，无需本机装 Maven |
| MySQL | 8.0+ | utf8mb4 |
| Node.js | 18+ / 20+ | 前端构建 |

### 1. 建库

```bash
# 建全部 18 张表，并种入演示账号、演示课程（可重复执行）
mysql -u root -p < wenjin-server/src/main/resources/schema.sql
# 演示选课等增量种子
mysql -u root -p wenjin < wenjin-server/src/main/resources/data.sql
```

`schema.sql` 种入：演示教师 `demo_teacher`（id=1）、演示学生 `demo_student`（id=2），初始密码均为 **`demo`**（首次登录自动升级为 BCrypt）；演示课程「软件工程」（`course.id=1`，`code=52015CC4B4`，已发布）。

### 2. 配置（数据库 / AI / 认证）

后端默认连 `localhost:3306/wenjin`，账号 `root` / 密码 `root`，可改 `application.yml` 或用环境变量 `DB_USER` / `DB_PASSWORD` 覆盖。

AI 能力（出题 / 标注 / 课程标准抽取）需要可插拔大模型 Key（OpenAI 兼容；**不配 Key 时 AI 功能走兜底/不可用，其余功能正常**）：

```powershell
# Windows PowerShell
$env:WENJIN_AI_API_KEY = "你的文本模型 Key"      # 默认 DeepSeek（deepseek-v4-pro）
$env:WENJIN_AI_VISION_API_KEY = "你的视觉模型 Key" # 默认 GLM（glm-5v-turbo），仅图片抽取需要
# 生产部署必设：令牌签名密钥（开发可不设，走默认值仅告警）
$env:WENJIN_AUTH_SECRET = "$(openssl rand -base64 48)"
```

```bash
# macOS / Linux
export WENJIN_AI_API_KEY="..."
export WENJIN_AI_VISION_API_KEY="..."
export WENJIN_AUTH_SECRET="$(openssl rand -base64 48)"
```

### 3. 启动后端（端口 8080）

```bash
cd wenjin-server
./mvnw spring-boot:run            # macOS / Linux（Windows 用 mvnw.cmd spring-boot:run）
# 备选（更稳）：打成可执行 jar 再运行
./mvnw -q -DskipTests package && java -jar target/wenjin-server.jar
```

### 4. 导入种子图谱与题库（演示课 `52015CC4B4`）

```bash
# 图谱（同课程重复导入 = 全量替换）
curl -X POST "http://localhost:8080/api/admin/graph/import?courseCode=52015CC4B4" \
     -H "Content-Type: application/json" \
     --data-binary "@wenjin-server/seed/问津_软件工程图谱_v0.3.json"

# 入口诊断种子题库（25 题，落「已通过」）
curl -X POST "http://localhost:8080/api/admin/question/import-bank?courseCode=52015CC4B4"
```

### 5. 启动前端（端口 5173）

```bash
cd wenjin-web
npm install
npm run dev
```

浏览器打开 **http://localhost:5173**（开发服务器把 `/api` 代理到后端 8080）。用 **`demo_teacher / demo`**（教师端）或 **`demo_student / demo`**（学生端）登录。

---

## 十、接口一览

统一前缀 `/api`，返回体 `Result<T>`（`code=0` 成功）。教师端（`/api/teacher/**`）经鉴权拦截器，仅教师可达。

| 分组 | 类级路径 | 主要职责 |
| --- | --- | --- |
| 用户 | `/api`（`/login`、`/register`） | 登录签发令牌、注册 |
| 课程 | `/api/course` | 课程广场 `available` / 我的课程 `my` / 选课 |
| 图谱查询 | `/api/graph/{courseId}` | 节点 + 边 + 掌握度（染色地图） |
| 入口诊断 | `/api/diagnostic` | 组卷 `paper` / 交卷判分 `submit` / 诊断回溯结果 |
| 学习路径 | `/api/path` | 路径生成 `generate` / 完成条目 `item/complete` / 查询 |
| AI 伴侣 | `/api/companion` | SSE 流式对话 `chat` / 会话列表 / 删除会话 |
| 成长档案 | `/api/growth` | 掌握度快照曲线聚合 |
| 图谱导入（管理） | `/api/admin/graph` | 图谱导入（校验 + 全量替换） |
| 题库（管理） | `/api/admin/question` | AI 出题 `generate` / 标注 `annotate` / 导入题库 `import-bank` |
| 教师·课程 | `/api/teacher/courses` | 建 / 删 / 发布 / 下架课程 |
| 教师·图谱 | `/api/teacher/graph` | 节点·边增改删、课程标准抽取（图片/文档） |
| 教师·题目 | `/api/teacher/questions` | 题目审核池、批量审批 |
| 教师·看板 | `/api/teacher/dashboard` | 课程学情聚合 |

---

## 十一、演进历程与进度

| 阶段 / 里程碑 | 目标 | 状态 |
| --- | --- | --- |
| 阶段一：地基 | 软件工程知识图谱构建 + 多课程数据库架构 + 染色地图渲染 | ✅ |
| 阶段二：题库与诊断入口 | AI 出题即标注、种子题库导入、入口诊断组卷 / 交卷判分 | ✅ |
| 阶段三：掌握度与点亮 | 掌握度计算（EWMA）+ 染色地图点亮 | ✅ |
| 阶段四：诊断与路径 | 认知诊断回溯（前置边逆推根因）+ 动态自适应学习路径 | ✅ |
| 阶段五：陪伴与回看 | AI 学习伴侣（SSE 流式、防幻觉）+ 成长档案 | ✅ |
| 阶段六：教师端闭环 | 图谱审核工作台 + 题目审核池 + 学情看板 | ✅ |
| 里程碑：课程标准抽取 | 图片 / 文档 AI 抽取 → 表格审核 → 召回率指标 → 全量替换 | ✅ |
| 里程碑：课程发布 | 教师发布 / 下架 + 学生课程广场自选 + deep-link 越权守卫 | ✅ |
| 里程碑：令牌认证 | HMAC 签名 Bearer 令牌 + BCrypt 密码（替换可伪造的裸身份头） | ✅ |

---

## 十二、测试与质量

- **后端**：JUnit 5 + Mockito + Spring Boot Test，**300+ 测试全绿**（含 `TokenService`、鉴权全链路集成测试 `StudentAuthChainIntegrationTest` 等）。
  ```bash
  cd wenjin-server && ./mvnw test
  ```
- **前端**：`npm run build` 构建校验；`wenjin-web/e2e/` 下 Playwright + 系统 Edge 真机验收脚本（多课程切换、课程发布闭环等，均真机双绿）。
- **代码评审**：关键里程碑均经多角色子代理评审（spec 合规 + 代码质量 + 全分支安全复盘）后合入。

---

> 详细设计与实施计划见 `docs/superpowers/specs/`、`docs/superpowers/plans/`。
