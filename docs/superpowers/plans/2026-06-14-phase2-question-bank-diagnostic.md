# 问津 阶段二：题库 + 入口诊断测试 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 每个任务遵循 TDD（见 [[wenjin-testing]] 的跑测方式）。

**Goal:** 打通"图谱有了 → 学生能做一套入口诊断题并交卷留痕"的链路：AI 出题即标注、存量题标注、入口诊断组卷、作答判分写库，前端诊断页接真实接口。本阶段不算掌握度（留阶段三）。

**Architecture:** 后端在已建好的 14 张表上补业务逻辑（`question/question_option/question_node/answer_record`），新增"知识图谱白名单查询 → AI 客户端（真模型，接口隔离便于 mock）→ 出题/标注校验落库 → 组卷 → 交卷判分"五段。AI 走 OpenAI 兼容协议（默认 DeepSeek），用 Spring 内置 `RestClient` 直连，接口 `QuestionAiClient` 隔离便于单测 mock。组卷需要题池：把设计稿 25 题作为种子题库（`status=已通过`）导入，AI 生成题进 `待审核`。前端装 vue-router，把设计稿 `Diagnostic.vue` 移植进功能版并接真实接口。

**Tech Stack:** Spring Boot 3.3.5 / Java 21 / MyBatis-Plus 3.5.7 / MySQL 8（后端）；Vue 3 + Vite 5 + Pinia + vue-router（前端）。AI：OpenAI 兼容 `/chat/completions`，JSON 模式。

**关键既有约定（实现者必读）：**
- 统一返回体 `Result<T>`（`code=0` 成功）、业务异常 `BusinessException(ResultCode, msg[, detail])`、全局异常处理已就位（见 `common/`）。新错误码加到 `ResultCode`。
- 实体用 Lombok `@Data` + MyBatis-Plus `@TableName/@TableId(AUTO)`，下划线↔驼峰自动映射（见 `entity/KgNode.java`）。Mapper `extends BaseMapper<T>` 加 `@Mapper`（见 `mapper/KgNodeMapper.java`）。
- 关系类型用枚举 `support/RelationType`（前置=1/包含=2/相关=3/应用=4），中文标签↔TINYINT 互转。
- Service 单测用 Mockito mock 各 Mapper，**不连库**（见 `service/GraphServiceImplTest.java`）。注意 MyBatis-Plus `BaseMapper.insert` 有重载，mock 时写 `any(Question.class)` 不能裸 `any()`。
- 演示课程写死 `course.id=1` `code=52015CC4B4`；图谱节点 `KT01..KT42`（含 `KT02-1` 等细分点）。
- 跑测：PowerShell `Start-Process "$dir\mvnw.cmd" -ArgumentList "test" -WorkingDirectory $dir -RedirectStandardOutput .test.out -NoNewWindow -Wait -PassThru`，看 `Tests run:` 与退出码。**不是 git 仓库**，提交步骤跳过（无 git），改为"运行测试确认绿"。

**全局状态约定：**
- `question.status`：0=待审核，1=已通过，2=已驳回。种子题库=1，AI 产物=0。
- `question.type`：1=单选（本阶段只做单选）。`question.source`：1=学校题库，2=AI生成。
- distractor_map 落在 `question_option.point_node_code`（该错误选项暴露的薄弱知识点 node_code；正确项为空）。
- main_point→`question_node.weight=1`，sub_points→`weight=2`。
- 演示学生：`sys_user.id=2`（role=2）。前端写死 `student_id=2, course_id=1`。

---

## File Structure

**后端 新建：**
- `entity/Question.java`、`entity/QuestionOption.java`、`entity/QuestionNode.java`、`entity/AnswerRecord.java`
- `mapper/QuestionMapper.java`、`QuestionOptionMapper.java`、`QuestionNodeMapper.java`、`AnswerRecordMapper.java`
- `support/QuestionStatus.java`（状态常量/枚举）
- `service/GraphQueryService.java`(+`impl/GraphQueryServiceImpl.java`)：前置白名单查询
- `ai/QuestionAiClient.java`（接口）、`ai/dto/AiQuestion.java`、`ai/dto/AiAnnotation.java`、`ai/dto/AiKnowledgePoint.java`、`ai/dto/AiDistractor.java`
- `ai/OpenAiCompatibleQuestionAiClient.java`（真实现）、`config/AiProperties.java`
- `service/QuestionService.java`(+impl)：generate / annotate / importBank
- `service/DiagnosticService.java`(+impl)：composePaper / submit
- `controller/QuestionAdminController.java`、`controller/DiagnosticController.java`
- `dto/`：`GenerateResult.java`、`AnnotateItemResult.java`、`AnnotateRequest.java`、`PaperVO.java`、`PaperQuestionVO.java`、`SubmitRequest.java`、`SubmitResult.java`、`QuestionGradeVO.java`
- `seed/问津_软件工程题库_v0.1.json`（**由控制者预先创建**，25 题种子）
- `seed/migration_phase2.sql`（ALTER 增列 + 演示学生）

**后端 修改：**
- `resources/schema.sql`：`question` 加 `status`、`question_option` 加 `point_node_code`、演示学生 seed
- `resources/application.yml`：`wenjin.ai.*` 配置
- `pom.xml`：无需新依赖（RestClient 来自 spring-web；JSON 用已有 Jackson）

**前端 新建：**
- `src/router/index.js`、`src/api/diagnostic.js`、`src/views/Diagnostic.vue`、`src/views/Admin.vue`
**前端 修改：**
- `package.json`（加 vue-router）、`src/main.js`（use router）、`src/App.vue`（router-view + nav）

---

## Task 1: 数据层脚手架 + 前置白名单查询

**Files:**
- Modify: `wenjin-server/src/main/resources/schema.sql`
- Create: `wenjin-server/seed/migration_phase2.sql`
- Create: `entity/Question.java` `entity/QuestionOption.java` `entity/QuestionNode.java` `entity/AnswerRecord.java`
- Create: `mapper/QuestionMapper.java` `QuestionOptionMapper.java` `QuestionNodeMapper.java` `AnswerRecordMapper.java`
- Create: `support/QuestionStatus.java`
- Create: `service/GraphQueryService.java` + `service/impl/GraphQueryServiceImpl.java`
- Test: `service/GraphQueryServiceImplTest.java`

- [ ] **Step 1: schema.sql 改三处（只增不改）**
  - `question` 表在 `source` 后加：`` `status` TINYINT NOT NULL DEFAULT 0 COMMENT '审核状态：0=待审核,1=已通过,2=已驳回【阶段二补充】', ``
  - `question_option` 表在 `is_correct` 后加：`` `point_node_code` VARCHAR(32) DEFAULT NULL COMMENT '该错误选项暴露的薄弱知识点 node_code（distractor_map，正确项为空）【阶段二补充】', ``
  - 文件末尾演示种子追加：`INSERT INTO sys_user (id, username, password, real_name, role, status) VALUES (2, 'demo_student', 'demo', '演示学生', 2, 1);`

- [ ] **Step 2: 写迁移脚本 `seed/migration_phase2.sql`（保留既有数据，run-once）**
  ```sql
  USE `wenjin`;
  -- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，若已执行过会报 1060，可忽略
  ALTER TABLE `question` ADD COLUMN `status` TINYINT NOT NULL DEFAULT 0
      COMMENT '审核状态：0=待审核,1=已通过,2=已驳回【阶段二补充】' AFTER `source`;
  ALTER TABLE `question_option` ADD COLUMN `point_node_code` VARCHAR(32) DEFAULT NULL
      COMMENT 'distractor_map：该错误选项暴露的薄弱 node_code【阶段二补充】' AFTER `is_correct`;
  INSERT INTO `sys_user` (id, username, password, real_name, role, status)
      VALUES (2, 'demo_student', 'demo', '演示学生', 2, 1)
      ON DUPLICATE KEY UPDATE real_name=VALUES(real_name);
  ```

- [ ] **Step 3: 写四个实体**（仿 `entity/KgNode.java`：`@Data @TableName(...) @TableId(type=IdType.AUTO)`，字段对应表列驼峰）
  - `Question`：id, courseId, stem, type, difficulty, answer, analysis, source, status, createdAt, updatedAt
  - `QuestionOption`：id, questionId, optionKey, optionText, isCorrect, pointNodeCode
  - `QuestionNode`：id, questionId, nodeId, weight
  - `AnswerRecord`：id, studentId, courseId, questionId, studentAnswer, isCorrect, answeredAt

- [ ] **Step 4: 写四个 Mapper**（仿 `KgNodeMapper`，`@Mapper interface XxxMapper extends BaseMapper<Xxx> {}`）

- [ ] **Step 5: 写 `QuestionStatus`**
  ```java
  package com.wenjin.support;
  public final class QuestionStatus {
      private QuestionStatus() {}
      public static final int PENDING = 0;   // 待审核
      public static final int APPROVED = 1;  // 已通过
      public static final int REJECTED = 2;  // 已驳回
  }
  ```

- [ ] **Step 6: 写失败测试 `GraphQueryServiceImplTest`**（mock `KgNodeMapper`/`KgEdgeMapper`，不连库；仿 `GraphServiceImplTest` 结构）
  - 构造一条前置链：KT01→KT04→KT05→KT07（前置边，relation_type=1），外加一条包含边 KT07→KT07-2（relation_type=2，**不应**进白名单）。节点都属 courseId=1。
  - 断言 `whitelistOf(1, "KT07", 2)` 返回包含 `KT07`(目标)、`KT05`(1层前置)、`KT04`(2层前置)，**不含** `KT01`(3层，超出深度)、**不含**任何包含边邻居。
  - 断言深度 1：`whitelistOf(1,"KT07",1)` = {KT07, KT05}。
  - 断言目标无前置：`whitelistOf(1,"KT01",2)` = {KT01}。

- [ ] **Step 7: 运行测试确认 RED**（编译失败＝方法不存在即 RED）

- [ ] **Step 8: 实现 `GraphQueryService`**
  ```java
  public interface GraphQueryService {
      /** 目标节点 + 沿「前置」边逆向 1..depth 层前置节点的 node_code 集合（含目标自身）。 */
      java.util.Set<String> whitelistOf(Long courseId, String targetNodeCode, int depth);
      /** 课程全部 node_code（存量题标注白名单）。 */
      java.util.Set<String> allNodeCodes(Long courseId);
      /** node_code -> kg_node.id 映射（落库时把白名单 code 转 node_id 用）。 */
      java.util.Map<String, Long> codeToId(Long courseId);
  }
  ```
  实现要点：`nodeMapper.selectList(courseId)` 得节点（建 code↔id、id↔code 映射）；`edgeMapper.selectList(courseId)` 取 `relationType==1`（前置）边，建 `to_node_id -> [from_node_id]` 逆邻接（用 id，再转 code）。从目标 code 出发 BFS，逐层扩展前置，最多 depth 层，结果含目标自身。注意边存的是 from/to node_id（主键），需用 id↔code 映射换算。

- [ ] **Step 9: 运行测试确认 GREEN**

---

## Task 2: AI 客户端（接口 + DTO + OpenAI 兼容真实现 + 配置）

**Files:**
- Create: `ai/dto/AiKnowledgePoint.java` `ai/dto/AiDistractor.java` `ai/dto/AiQuestion.java` `ai/dto/AiAnnotation.java`
- Create: `ai/QuestionAiClient.java`
- Create: `config/AiProperties.java`
- Create: `ai/OpenAiCompatibleQuestionAiClient.java`
- Modify: `resources/application.yml`
- Test: `ai/OpenAiCompatibleQuestionAiClientTest.java`（只测 prompt 拼装 + 响应解析，不联网）

**AI 契约（出题与标注统一返回 JSON）：**
- 出题(Prompt 2) 入参：目标 node（code+name+章节）、数量 n、白名单（code+name 列表，含目标 1-2 层前置）。要求 LLM 只在白名单内出单选题，返回数组，每题：
  ```json
  {"stem":"...","options":[{"key":"A","text":"...","correct":true,"point_node_code":null},
                            {"key":"B","text":"...","correct":false,"point_node_code":"KT05"}],
   "analysis":"...","difficulty":3,
   "main_point":"KT07","sub_points":["KT05"]}
  ```
  约束写进 prompt：恰一个 correct=true；干扰项尽量给 point_node_code（须在白名单内）；main_point=目标、sub_points⊆白名单。
- 标注(Prompt 3) 入参：题干+选项、全图白名单。返回单对象：`{"main_point":"KT10","sub_points":[...],"distractors":[{"key":"B","point_node_code":"KT07"}],"reason":"..."}`；**超纲则 `main_point:null` 并在 `reason` 说明**，不强行标注。

- [ ] **Step 1: 写 DTO（Lombok `@Data`）**
  - `AiDistractor`：optionKey, text, correct, pointNodeCode
  - `AiKnowledgePoint`：nodeCode（备用）
  - `AiQuestion`：stem, List<AiDistractor> options, analysis, difficulty, mainPoint, List<String> subPoints
  - `AiAnnotation`：mainPoint(可空), List<String> subPoints, List<AiDistractor> distractors, reason

- [ ] **Step 2: 写接口 `QuestionAiClient`**
  ```java
  public interface QuestionAiClient {
      /** 出题即标注：白名单内生成 count 道单选题。 */
      java.util.List<AiQuestion> generate(String targetNodeCode, String targetName,
              String chapter, int count, java.util.List<String[]> whitelist /*[code,name]*/);
      /** 存量题标注：在白名单内标注；超纲返回 mainPoint=null。 */
      AiAnnotation annotate(String stem, java.util.List<String[]> options /*[key,text]*/,
              java.util.List<String[]> whitelist);
  }
  ```

- [ ] **Step 3: 写配置 `AiProperties`** (`@ConfigurationProperties("wenjin.ai")`，字段 baseUrl/apiKey/model/temperature/enabled)，并在 `application.yml` 加：
  ```yaml
  wenjin:
    ai:
      enabled: ${WENJIN_AI_ENABLED:true}
      base-url: ${WENJIN_AI_BASE_URL:https://api.deepseek.com}
      api-key: ${WENJIN_AI_API_KEY:}
      model: ${WENJIN_AI_MODEL:deepseek-chat}
      temperature: 0.4
  ```
  在 `WenjinServerApplication` 加 `@EnableConfigurationProperties(AiProperties.class)`（或给 AiProperties 加 `@Component`）。

- [ ] **Step 4: 写失败测试**（把 prompt 拼装与 JSON 解析抽成包级可见静态方法 `buildGeneratePrompt(...)`、`parseQuestions(String json)`、`parseAnnotation(String json)` 便于单测）
  - `parseQuestions` 喂一段含 markdown ```json 围栏的样例响应 → 返回 1 题，options 4 个、main_point 正确、干扰项 point_node_code 解析正确。
  - `buildGeneratePrompt` 断言输出包含全部白名单 code 与"只在以下知识点范围内"等约束词、目标 node、数量 n。
  - `parseAnnotation` 喂 `{"main_point":null,"reason":"超纲..."}` → mainPoint==null 且 reason 非空。

- [ ] **Step 5: 运行测试确认 RED**

- [ ] **Step 6: 实现 `OpenAiCompatibleQuestionAiClient`**（`@Component`，构造注入 `AiProperties`；用 `RestClient.create(baseUrl)` POST `/v1/chat/completions`，header `Authorization: Bearer {apiKey}`，body `{model, messages:[{role:user,content:prompt}], temperature, response_format:{type:"json_object"}}`）
  - prompt 用上面"AI 契约"。解析：取 `choices[0].message.content`，剥离 ```json 围栏，Jackson `ObjectMapper` 读成 DTO（generate 时若返回对象含 `questions` 数组则取之，也兼容裸数组）。
  - 失败（无 apiKey / 网络异常 / 解析失败）抛 `BusinessException(ResultCode.AI_ERROR, ...)`。新增 `ResultCode.AI_ERROR(1002,"AI服务调用失败")`。

- [ ] **Step 7: 运行测试确认 GREEN**

---

## Task 3: 出题流水线 QuestionService.generate（Prompt 2 校验+落库+去重+重试）

**Files:**
- Create: `dto/GenerateResult.java`（generated, dropped, duplicated, questionIds, message）
- Create: `service/QuestionService.java`（先只放 `generate`）+ `service/impl/QuestionServiceImpl.java`
- Test: `service/QuestionServiceImplTest.java`

**generate 流程：** 取 courseId（演示=1）；`whitelist = graphQueryService.whitelistOf(courseId, nodeCode, 2)`；调 `aiClient.generate(...)`；逐题**代码侧校验**：(a) 恰一个 correct 选项且 answer 在 options 内；(b) main_point∈白名单、sub_points⊆白名单、所有干扰项 point_node_code∈白名单(或空)。校验不过的题丢弃；若本轮有效题数 < 期望，**整体重试一次**（再调一次 aiClient，合并有效题）。落库前**去重**：同 courseId 下 stem（trim 后）已存在则跳过（计入 duplicated，不报错）。有效且不重复的题：insert question(status=PENDING, source=2, type=1)→option(带 point_node_code)→question_node(main_point weight1 / sub_points weight2，code 经 `codeToId` 转 node_id)。返回 `GenerateResult`。

- [ ] **Step 1: 写 `GenerateResult`（`@Data`，全字段）**

- [ ] **Step 2: 写失败测试**（mock `GraphQueryService` + `QuestionAiClient` + 四个 Mapper）
  - 用例A 全合法：mock whitelist={KT07,KT05,KT04}；aiClient 返回 2 道合法题 → 断言 `questionMapper.insert` 调 2 次、status=PENDING、`generated==2`，`questionNodeMapper.insert` 含 weight=1 主点。
  - 用例B 非法被丢+重试：第一次返回 1 合法 + 1 非法（干扰项 point_node_code="KTXX" 不在白名单）→ 丢非法；断言 aiClient.generate 被调 **2 次**（重试一次），`dropped>=1`。
  - 用例C 去重：mock `questionMapper.selectList`（或 count）使某 stem 已存在 → 该题不 insert，`duplicated==1`，不抛异常。
  - 用例D answer 不在选项/无 correct：丢弃。

- [ ] **Step 3: 运行测试确认 RED**

- [ ] **Step 4: 实现 `QuestionServiceImpl.generate`**（`@Transactional`；校验/去重/重试如上；演示 courseId 用 `@Value("${wenjin.demo.course-id:1}")`，在 application.yml 的 `wenjin.demo` 下加 `course-id: 1`）

- [ ] **Step 5: 运行测试确认 GREEN**

---

## Task 4: 标注流水线 QuestionService.annotate（Prompt 3，超纲不强标）

**Files:**
- Create: `dto/AnnotateRequest.java`（List<Item>：stem, List<Option>{key,text,correct}）、`dto/AnnotateItemResult.java`（stem, mainPoint(可空), subPoints, reason, persisted）
- Modify: `service/QuestionService.java`（加 `annotate`）+ impl
- Test: 扩展 `QuestionServiceImplTest`

**annotate 流程：** `whitelist = graphQueryService.allNodeCodes(courseId)`；逐题调 `aiClient.annotate`；若 `mainPoint==null` → 该题不落 question_node（不强行标注），结果 `persisted=false`、带 reason 回传；否则校验 mainPoint/subPoints/distractors∈白名单，落库 question(status=PENDING, source=1/3)+options+question_node。返回每题结果列表。

- [ ] **Step 1: 写 DTO**
- [ ] **Step 2: 写失败测试**
  - 在纲内：mock annotate 返回 mainPoint=KT10 → 断言 persisted=true、question_node 落 weight=1、结果 mainPoint=KT10。
  - **超纲**：mock annotate 返回 mainPoint=null,reason="超出本课程范围" → 断言不调 `questionNodeMapper.insert`、结果 mainPoint=null 且 reason 透传、persisted=false。
- [ ] **Step 3: RED**
- [ ] **Step 4: 实现 annotate**
- [ ] **Step 5: GREEN**

---

## Task 5: 题库种子导入 + 管理接口（generate/annotate/import-bank）

**Files:**
- Create: `dto/ImportBankResult.java`（imported, skipped）
- Modify: `service/QuestionService.java`（加 `importBank(courseCode)`）+ impl
- Create: `controller/QuestionAdminController.java`
- 依赖文件：`seed/问津_软件工程题库_v0.1.json`（控制者已建，结构见下）
- Test: 扩展 `QuestionServiceImplTest`（importBank 用 mock mapper + classpath/文件读取桩）

**种子 JSON 结构**（控制者提供，25 题，已映射 node_code，答案除"敏捷"那题在 B 外其余在 A）：
```json
{"courseCode":"52015CC4B4","questions":[
  {"stem":"瀑布模型最显著的特点是什么？","nodeCode":"KT02-1","chapter":"软件工程概述",
   "options":[{"key":"A","text":"各阶段顺序进行...","correct":true},{"key":"B",...},...],
   "analysis":""}, ... ]}
```

**importBank 流程：** 读 JSON（`new ClassPathResource` 取不到则按工作目录 `seed/...` 读，或由 controller 传入流）；按 courseCode 定位 courseId；逐题 insert question(**status=APPROVED**, source=1, type=1)+options(correct 标志)+question_node(nodeCode→node_id, weight=1)；同 stem 已存在则 skip。返回 imported/skipped。

- [ ] **Step 1: 写 `ImportBankResult`**
- [ ] **Step 2: 写失败测试**：mock 让题库读到 2 题、mock codeToId 命中 → 断言 insert question 2 次且 status=APPROVED；其中一题 stem 已存在 → skip。
- [ ] **Step 3: RED**
- [ ] **Step 4: 实现 importBank**（JSON 读取用 Jackson；文件定位：优先 `seed/问津_软件工程题库_v0.1.json` 相对工作目录，找不到再 classpath）
- [ ] **Step 5: GREEN**
- [ ] **Step 6: 写 `QuestionAdminController`**（仿 `GraphAdminController`，`@RequestMapping("/api/admin/question")`）
  - `POST /generate?nodeCode=&count=` → `Result.ok(questionService.generate(nodeCode,count))`
  - `POST /annotate` (@RequestBody AnnotateRequest) → `Result.ok(questionService.annotate(req))`
  - `POST /import-bank?courseCode=` → `Result.ok(questionService.importBank(courseCode))`
- [ ] **Step 7: 编译+全量测试确认 GREEN**

---

## Task 6: 入口诊断组卷 GET /api/diagnostic/paper

**Files:**
- Create: `dto/PaperVO.java`（courseId, total, List<PaperQuestionVO>）、`dto/PaperQuestionVO.java`（**不含答案**：questionId, stem, chapter, type, List<OptionVO>{key,text}）
- Create: `service/DiagnosticService.java`（先放 `composePaper`）+ impl
- Create: `controller/DiagnosticController.java`
- Test: `service/DiagnosticServiceImplTest.java`

**composePaper 流程（PRD 6.1 分层抽样）：** 取 courseId 下 `status=APPROVED` 题（join 出每题主知识点的 chapter）；按章节分组；目标总量 25（区间 20–30，配置 `wenjin.diagnostic.paper-size:25`）；按各章题量占比分配名额（每有题的章至少 1 题），章内优先选重点(is_key)/枢纽(前置出入度高)节点对应题；汇总去重，截到目标量（池不足则全取）。产出 `PaperVO`，**逐题剔除 answer/is_correct/point_node_code**。

- [ ] **Step 1: 写 `PaperVO`/`PaperQuestionVO`（确保无答案字段）**
- [ ] **Step 2: 写失败测试**（mock mapper 返回跨 5 个章节、共 30 道 APPROVED 题及其选项/章节）
  - 断言返回数量 ∈ [20,30]；
  - 断言覆盖 ≥4 个不同 chapter（章节分布合理）；
  - 断言任一 `PaperQuestionVO`/`OptionVO` 序列化无 `answer`/`isCorrect`/`correct`/`pointNodeCode` 字段（反射或 Jackson 序列化断言）。
- [ ] **Step 3: RED**
- [ ] **Step 4: 实现 composePaper**
- [ ] **Step 5: GREEN**
- [ ] **Step 6: 写 `DiagnosticController`** `@RequestMapping("/api/diagnostic")`，`GET /paper?courseId=` → `Result.ok(...)`

---

## Task 7: 交卷判分 POST /api/diagnostic/submit

**Files:**
- Create: `dto/SubmitRequest.java`（studentId, courseId, List<Answer>{questionId, optionKey}）、`dto/SubmitResult.java`（total, correctCount, List<QuestionGradeVO>）、`dto/QuestionGradeVO.java`（questionId, correct, correctKey）
- Modify: `service/DiagnosticService.java`（加 `submit`）+ impl
- Modify: `controller/DiagnosticController.java`（加 submit）
- Test: 扩展 `DiagnosticServiceImplTest`

**submit 流程：** 逐题取该题正确 option_key（`questionOptionMapper` where questionId and isCorrect=1）；与学生所选比对得 isCorrect；写 `answer_record`(studentId, courseId, questionId, **studentAnswer=所选 key**, isCorrect)；返回每题 `{questionId, correct, correctKey}` 及总分。**本阶段不算掌握度**（不碰 student_mastery）。

- [ ] **Step 1: 写 DTO**
- [ ] **Step 2: 写失败测试**：mock 两题正确键 A、B；学生答 A、C → 断言 answer_record.insert 调 2 次、studentAnswer 分别为"A"/"C"、isCorrect 为 1/0；correctCount=1；返回每题 correct 正确。
- [ ] **Step 3: RED** → **Step 4: 实现** → **Step 5: GREEN**
- [ ] **Step 6: controller 加 `POST /submit`** (@RequestBody SubmitRequest)

---

## Task 8: 前端——vue-router + 诊断页接真实接口

**Files:**
- Modify: `wenjin-web/package.json`（deps 加 `"vue-router": "^4.3.0"`），跑 `npm install`
- Create: `src/router/index.js`（createWebHistory；`/map`→ColorMap，`/diagnostic`→Diagnostic，`/admin`→Admin，`/` 重定向 `/diagnostic`）
- Modify: `src/main.js`（`.use(router)`）
- Modify: `src/App.vue`（顶部极简 nav 链接 + `<router-view/>`）
- Create: `src/api/diagnostic.js`（复用 `api/graph.js` 的 axios 实例模式：`fetchPaper(courseId)` GET `/diagnostic/paper`；`submitPaper(payload)` POST `/diagnostic/submit`）
- Create: `src/views/Diagnostic.vue`（移植设计稿 `问津/wenjin-vue/src/views/Diagnostic.vue`）

**移植要点：**
- 保留单题聚焦、进度条、低焦虑文案与交互（上一题/跳过/下一题/完成）。
- **去掉**对 `ThemeToggle`、`useViewport`、全局 class 的依赖：`useViewport` 用本地 `window.innerWidth` 的 ref + resize 监听替代；`ThemeToggle` 删除；`wj-opt:hover` 等留在本组件 scoped style。
- **令牌局部化**：设计稿用 `var(--bg/--ink/--acc/--card/--card2/--line/--mut/--accSoft)` 等，功能版 tokens.css 没有这些。在 Diagnostic 根容器 class 上用 scoped style 定义这组 CSS 变量（浅色低焦虑配色），**不要**改全局 `--bg/--line`（会破坏 ColorMap 深色）。
- 数据源替换：`onMounted` 调 `fetchPaper(1)`，把后端 `PaperQuestionVO` 映射为组件题目结构（`q`=stem、`o`=options.text 数组、`c`=chapter、保留 questionId 与 optionKey 顺序）。"共 N 题"用真实 total，不写死 25。
- 交卷：完成时收集 `{questionId, optionKey}` 列表 → `submitPaper({studentId:2,courseId:1,answers})` → 用返回的每题对错渲染结果（完成态展示"答对 X / 共 N"，并可列出逐题对错）。
- **DEV 测试钩子**（仿 ColorMap 的 `__wj`）：`if (import.meta.env.DEV) window.__wjDiag = { state, pick, next, submit, result }`，供 Playwright 程序化答题/交卷断言。
- 容错：加载中/失败/空题池的占位提示。

- [ ] **Step 1:** package.json 加 vue-router 并 `npm install`
- [ ] **Step 2:** 写 router、改 main.js、改 App.vue（nav：诊断 / 染色地图 / 管理）
- [ ] **Step 3:** 写 api/diagnostic.js
- [ ] **Step 4:** 写 Diagnostic.vue（按移植要点）
- [ ] **Step 5: 构建确认**：`npm run build` 通过（无未解析 import / 语法错）。前端无单测框架，端到端留最终验收 Playwright。

---

## Task 9: 最简管理操作入口 Admin.vue

**Files:**
- Create: `src/views/Admin.vue`（已在 Task 8 注册路由 `/admin`）

**内容（不做精美 UI）：** 三个操作块：
- 出题：输入 nodeCode（默认 KT07）、数量（默认 5）→ 调 `POST /api/admin/question/generate` → 展示返回 JSON（generated/dropped/duplicated）。
- 标注：一个 textarea 贴题目 JSON（AnnotateRequest）→ `POST /api/admin/question/annotate` → 展示每题 main_point/null+reason。
- 导入题库：按钮 → `POST /api/admin/question/import-bank?courseCode=52015CC4B4` → 展示 imported/skipped。
用 `api/graph.js` 同款 axios（baseURL `/api`）。

- [ ] **Step 1:** 写 Admin.vue（fetch + 结果展示，朴素样式）
- [ ] **Step 2:** `npm run build` 通过

---

## 最终验收（全链路真机，控制者执行）

> 需要后端编译运行 + DB 迁移 + 真实 AI Key。AI Key 验收前向用户索取（设 `WENJIN_AI_API_KEY` 环境变量）。

- [ ] 后端：执行 `seed/migration_phase2.sql`（加列+演示学生）；`mvnw.cmd test` 全绿；打包 `java -jar` 启动。
- [ ] 导入题库：`POST /api/admin/question/import-bank?courseCode=52015CC4B4` → imported≈25。
- [ ] 组卷：`GET /api/diagnostic/paper?courseId=1` → 题数 ∈[20,30]、章节分布合理、**响应无答案字段**。
- [ ] 出题（带真 Key）：`POST /api/admin/question/generate?nodeCode=KT07&count=5` → distractor_map 的 node_code 全合法、落 `status=待审核`；再调一次同节点 → 不报错、重复题不入库。
- [ ] 标注超纲：构造一道明显超纲题（如"量子纠缠"）喂 `/annotate` → main_point=null + reason。
- [ ] 前端 e2e（Playwright + 系统 Edge，见 [[wenjin-testing]]）：进入 `/diagnostic` → 经 `window.__wjDiag` 程序化答完 → 交卷 → 校验每题对错与"答对 X/共 N" → 查 DB `answer_record` 落库且 `student_answer` 记录了所选选项。
- [ ] 同知识点重复出题不报错、题目不重复入库（在出题步骤一并验证）。

## 收尾
- [ ] 更新记忆：[[wenjin-schema-deviations]] 增 `question.status`/`question_option.point_node_code`；[[wenjin-project]] 修正"AI 阶段三才接"为"阶段二已接真模型（OpenAI 兼容/DeepSeek）"、记下种子题库与诊断链路起点；新增 [[wenjin-phase2]] 记接口与组卷/判分口径。
- [ ] 用 superpowers:finishing-a-development-branch 收束（无 git，则做最终 code-review 子代理 + 汇总）。

## Self-Review 覆盖核对
- 启用 question/answer_record 表 ✓(T1 实体/Mapper + T3/T5/T7 写入)；AI 出题即标注 Prompt2 + 白名单 + 代码侧校验 + 丢弃重试一次 ✓(T1 白名单/T2 AI/T3 校验)；存量题标注 Prompt3 全图白名单 + 超纲 main_point=null ✓(T4)；组卷分层抽样 20–30 不含答案 ✓(T6)；交卷判分写 answer_record 含所选选项、不算掌握度 ✓(T7)；前端 Diagnostic 接真实接口保留低焦虑视觉 ✓(T8)；最简管理入口 ✓(T9)；五条验收 ✓(最终验收)。
