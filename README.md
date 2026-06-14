# 问津 · 知识图谱驱动的个性化学习导航系统

> 大连东软信息学院首届「AI+教育」应用创新大赛 · 赛道一（教育图谱）
> 演示课程：软件工程

本仓库当前为 **第一阶段：地基链路**。本阶段只打通一条链路：
**数据库建表 → 图谱 JSON 导入 → 前端力导向图渲染**。
后续的诊断、路径、AI 等功能在此地基上迭代。

```
Fordo/
├── wenjin-server/        # 后端：Spring Boot 3 + Java 21 + MyBatis-Plus + MySQL 8（端口 8080）
│   ├── src/main/resources/schema.sql                 # 建库建表脚本（14 张表，可重复执行）
│   └── seed/问津_软件工程图谱_v0.3.json               # 软件工程图谱种子数据（42 节点 / 53 边）
├── wenjin-web/           # 前端：Vue 3 + Vite + Pinia + ECharts 5（端口 5173）
└── 问津/wenjin-vue/      # 视觉设计稿（仅参考，后续替换功能版样式，本阶段不依赖）
```

---

## 一、环境要求

| 组件 | 版本要求 | 说明 |
| --- | --- | --- |
| JDK | 21 | 后端 `java.version=21` |
| Maven | 3.6.3+ | 仓库内置 **Maven Wrapper（`mvnw`，已固定 3.9.9）**，无需本机装 Maven |
| MySQL | 8.0+ | 字符集 utf8mb4 |
| Node.js | 18+ / 20+ | 前端构建 |
| npm | 9+ | 随 Node 安装 |

> ⚠ 若本机 Maven 低于 3.6.3（Spring Boot 3.3 的编译插件要求 3.6.3+），请直接用仓库内的
> `mvnw` / `mvnw.cmd`，它会自动下载并使用 Maven 3.9.9。

---

## 二、建库（执行 schema.sql）

`schema.sql` 会自动 `CREATE DATABASE IF NOT EXISTS wenjin`、建全 14 张表（均带中文注释），
并种入一条演示教师与演示课程（`course.id=1`, `code=52015CC4B4`）。脚本**可重复执行**
（每张表先 `DROP IF EXISTS`）。

```bash
# 在 wenjin-server 目录下，使用你的 MySQL 账号执行：
mysql -u root -p < src/main/resources/schema.sql
```

> Windows PowerShell 写法：
> ```powershell
> Get-Content -Raw -Encoding UTF8 "src/main/resources/schema.sql" | mysql -u root -p
> ```

执行后库名为 `wenjin`。

---

## 三、配置数据库连接

后端默认连接 `localhost:3306/wenjin`，账号 `root` / 密码 `root`。
如与本机不同，二选一修改：

- 改 `wenjin-server/src/main/resources/application.yml` 的 `spring.datasource.username/password`；
- 或用环境变量覆盖（无需改代码）：
  ```powershell
  $env:DB_USER = "root"; $env:DB_PASSWORD = "你的密码"
  ```

---

## 四、启动后端（端口 8080）

```bash
cd wenjin-server
# Windows
mvnw.cmd spring-boot:run
# macOS / Linux
./mvnw spring-boot:run
```

看到 `Started WenjinServerApplication` 即启动成功。

---

## 五、导入种子数据（图谱 JSON）

后端启动后，把 `seed/问津_软件工程图谱_v0.3.json` 导入演示课程（`courseCode=52015CC4B4`）。
**同一课程重复导入 = 全量替换**，可反复执行不会重复。

```bash
# 通用 curl（在 wenjin-server 目录下执行）
curl -X POST "http://localhost:8080/api/admin/graph/import?courseCode=52015CC4B4" \
     -H "Content-Type: application/json" \
     --data-binary "@seed/问津_软件工程图谱_v0.3.json"
```

Windows PowerShell：

```powershell
curl.exe -X POST "http://localhost:8080/api/admin/graph/import?courseCode=52015CC4B4" `
  -H "Content-Type: application/json" `
  --data-binary "@seed/问津_软件工程图谱_v0.3.json"
```

成功返回：

```json
{ "code": 0, "message": "成功",
  "data": { "courseId": 1, "courseCode": "52015CC4B4", "courseName": "软件工程", "nodeCount": 42, "edgeCount": 53 } }
```

校验失败（如孤立 ID / 成环前置边）返回 `code=1001`，`data.issues` 为逐条错误明细。

---

## 六、启动前端（端口 5173）

```bash
cd wenjin-web
npm install
npm run dev
```

浏览器打开 **http://localhost:5173**。前端开发服务器把 `/api` 代理到后端 8080，
默认按 `courseId=1` 渲染染色地图（写死的演示课程，见 `src/views/ColorMap.vue`）。

---

## 七、接口一览（本阶段）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/admin/graph/import?courseCode=` | 导入图谱（校验 + 全量替换） |
| GET | `/api/graph/{courseId}` | 查询课程全部节点与边（掌握度本阶段统一 `unlearned`） |

统一返回体 `Result<T>`：`{ code, message, data }`，`code=0` 为成功。

---

## 八、表结构说明（相对 PRD §4.5 的「只增不改」补充）

为承载 v0.3 图谱数据与「按 courseCode 导入」，在不改动 PRD 既有字段的前提下补充了少量字段
（已在后续阶段不再改表的前提下一次补齐）：

| 表 | 新增字段 | 原因 |
| --- | --- | --- |
| `course` | `code`（课程业务编码，UNIQUE） | 对应 JSON 的 `course.code` 与导入入参 `courseCode`，用于定位课程 |
| `kg_node` | `is_key`（是否重点）、`bloom`（布卢姆层级）、`node_note`（备注） | JSON 节点含这些字段；染色地图按 `is_key` 决定节点大小 |
| `kg_edge` | `relation_note`（关系备注） | JSON 边含 `note` 评审说明 |

其余 11 张表严格按 PRD §4.5 建好，本阶段不实现其业务逻辑（仅 `course`/`kg_node`/`kg_edge` 有逻辑）。

---

## 九、验收自检对照

| # | 验收标准 | 如何验证 |
| --- | --- | --- |
| 1 | schema.sql 空库执行成功，14 张表带中文注释 | 执行第二步；`SHOW FULL COLUMNS FROM kg_node;` 看注释 |
| 2 | 导入 v0.3 成功（42 节点 / 53 边） | 第五步，返回 `nodeCount=42, edgeCount=53` |
| 2b | 改一条边 target 为不存在 ID，被拒并报错 | 见下方「构造校验失败用例」 |
| 3 | 人造成环前置边，报出环路径 | 见下方「构造校验失败用例」 |
| 4 | 重复导入不重复（全量替换） | 连续导入两次，`GET /api/graph/1` 节点/边数不变 |
| 5 | 浏览器渲染 42 节点、三种边样式、点击抽屉、前置跳转、章节筛选 | 第六步打开页面交互 |

### 构造校验失败用例

把 `seed` 中 JSON 复制一份，将某条边的 `target` 改成 `KT999`（不存在），导入会返回：

```json
{ "code": 1001, "message": "图谱校验失败，共 1 处问题，已整体拒绝导入",
  "data": { "issues": [ { "category": "MISSING_NODE", "message": "第 N 条边的 target「KT999」在节点集中不存在" } ] } }
```

构造一条成环前置边（例如新增 `{"source":"KT02","target":"KT01","type":"前置"}`，与既有
`KT01→KT02` 成环），导入返回：

```json
{ "code": 1001, "message": "图谱校验失败，共 1 处问题，已整体拒绝导入",
  "data": { "issues": [ { "category": "CYCLE", "message": "「前置」关系存在环路：KT01 → KT02 → KT01" } ] } }
```

---

## 十、明确不做（本阶段范围）

不做登录与权限、掌握度计算、诊断、路径、题库、AI 集成、移动端适配；
不引入 Redis / 消息队列 / Docker 编排。
