# 问津 · 前端

> 知识图谱驱动的个性化学习导航系统 —— Vue 3 单页应用

「使子路问津焉」。不知卡在何处，便来此问路：用知识图谱定位学情、回溯诊断根因、规划学习路径。

---

## 快速开始

```bash
npm install
npm run dev      # 开发服务器（默认 http://localhost:5173）
npm run build    # 生产构建，产物在 dist/
npm run preview  # 本地预览构建产物
```

> **无需安装也能看**：直接用浏览器打开根目录的 `preview.html` 即可预览全部页面
> （它通过 CDN 在浏览器里实时编译 `.vue` 源码，仅用于快速预览/演示，正式开发请用 `npm run dev`）。

---

## 技术栈

| 关注点 | 选择 |
| --- | --- |
| 框架 | Vue 3（`<script setup>` 组合式 API） |
| 路由 | Vue Router 4（hash 模式，方便静态部署/直接打开） |
| 构建 | Vite 5 |
| 样式 | 内联 style + 一份 CSS 设计令牌（`src/styles/tokens.css`），无 UI 组件库 |
| 数据 | 静态知识图谱 `public/data/graph.json`（42 个知识点 + 依赖边） |

无第三方 UI 库、无图表库 —— 染色地图的力导向布局是纯 JS 实现（`src/utils/graphLayout.js`），
保证与设计稿一致的星图质感、印章描边与回溯链发光效果。

---

## 目录结构

```
wenjin-vue/
├── index.html                 # Vite 入口
├── preview.html               # 免构建预览（CDN 实时编译，演示用）
├── vite.config.js
├── package.json
├── public/
│   └── data/graph.json        # 课程知识图谱数据
└── src/
    ├── main.js                # 应用入口
    ├── App.vue                # 根组件 + 路由出口（跨页淡入淡出过渡）
    ├── router/index.js        # 路由表（12 条）
    ├── styles/tokens.css      # 双主题设计令牌 + 关键帧 + 交互工具类
    ├── composables/
    │   ├── useTheme.js        # 暖纸/墨夜主题，持久化到 localStorage
    │   ├── useViewport.js     # 响应式窗口宽度（断点布局）
    │   └── useGraphData.js    # 单例加载并共享 graph.json
    ├── utils/graphLayout.js   # 力导向布局算法（三张图谱页共用）
    ├── components/
    │   ├── TopBar.vue         # 通用顶栏
    │   ├── ThemeToggle.vue    # 主题切换段控
    │   ├── NavLink.vue        # 顶栏导航链接
    │   ├── IOSFrame.vue       # iPhone 外壳（移动端预览）
    │   └── MobilePhone.vue    # 移动端 App（4 Tab + 抽屉 + 练习流）
    └── views/                 # 12 个页面
        ├── Login.vue              # 登录 / 课程选择
        ├── ColorMap.vue          # 染色地图（学生端主视图）
        ├── Diagnostic.vue        # 入口诊断（25 题答题流）
        ├── DiagnosticResult.vue  # 诊断结果（根因回溯链）
        ├── LearningPath.vue      # 学习路径（四步时间线）
        ├── KnowledgePoint.vue    # 知识点详情
        ├── Companion.vue         # AI 学习伴侣（对话）
        ├── Growth.vue            # 成长档案（前后对比）
        ├── MobileMap.vue         # 移动端地图（两个形态并排）
        ├── TeacherGraphReview.vue   # 图谱审核工作台
        ├── TeacherQuestionPool.vue  # 题目审核池
        └── TeacherDashboard.vue     # 学情看板
```

---

## 路由一览

| 路径 | 页面 | 端 |
| --- | --- | --- |
| `/` | 登录 / 课程选择 | 学生 |
| `/map` | 染色地图 | 学生 |
| `/diagnostic` | 入口诊断 | 学生 |
| `/result` | 诊断结果 | 学生 |
| `/path` | 学习路径 | 学生 |
| `/knowledge` | 知识点详情 | 学生 |
| `/companion` | AI 学习伴侣 | 学生 |
| `/growth` | 成长档案 | 学生 |
| `/mobile` | 移动端地图（两形态） | 学生 |
| `/teacher/graph` | 图谱审核工作台 | 教师 |
| `/teacher/questions` | 题目审核池 | 教师 |
| `/teacher/dashboard` | 学情看板 | 教师 |

页面间通过 `<router-link>` 跳转，切换带统一的淡入淡出过渡（定义在 `tokens.css` 的 `.page-fade-*`）。

---

## 设计系统

- **主题**：`暖纸`（浅色，日常学习）与 `墨夜`（深色，图谱主视图 / 演示主力），
  由 `<html data-theme>` 切换，全部组件复用同一组 CSS 变量，选择持久化到 `localStorage('wj_theme')`。
- **字体**：标题用思源宋体（Noto Serif SC），正文用系统无衬线（PingFang / HarmonyOS Sans）。
- **强调色**：朱砂红 `--acc`，用于当前位置、根因、主操作按钮。
- **节点染色**：已掌握（绿）/ 薄弱待修（琥珀）/ 未学（灰）/ 当前位置（朱砂描边）。

---

## 关于图谱实现的说明

设计提示词建议「Vue 3 + ECharts」。本项目的三张图谱页（染色地图、学情看板、图谱审核工作台）
保留了已审定的**自研力导向布局**（`src/utils/graphLayout.js`）而非改用 ECharts —— 因为现有视觉
（章节锚点、星空背景、标签贪心避让、回溯链发光、印章描边）已反复打磨确认。布局算法与框架无关，
若后续需要替换为 ECharts，可在不动页面结构的前提下单独替换该模块。

---

## 数据

`public/data/graph.json` 为静态课程图谱（软件工程，v0.3 教师已审）。各页面中的掌握度、诊断结论、
练习题目等为演示数据，硬编码在对应组件内，便于后续对接真实接口时定位替换。
