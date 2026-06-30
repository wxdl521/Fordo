-- ════════════════════════════════════════════════════════════════════════
--  问津 · 知识图谱驱动的个性化学习导航系统
--  数据库建表脚本（对应 PRD v1.1 §4.5，共 14 张表）
--
--  使用说明：
--    1. 本脚本可重复执行（每张表先 DROP IF EXISTS 再 CREATE）。
--    2. 字符集统一 utf8mb4，存储引擎 InnoDB。
--    3. 所有表、字段均带中文 COMMENT。
--    4. 外键为「逻辑外键」，不建物理约束（PRD §4.5.7 约定，应用层保证一致性），
--       因此 DROP 顺序无依赖要求。
--    5. 演示种子数据见 data.sql（本地开发需 schema.sql + data.sql；生产仅执行本脚本）。
--
--  本阶段（地基链路）仅 course / kg_node / kg_edge 三张表有业务逻辑，
--  其余 11 张表按 PRD 一次性建好，后续阶段不再改表。
--
--  ⚠ 相对 PRD §4.5 的物理设计，为承载 v0.3 图谱数据与「按 courseCode 导入」，
--    做了以下「只增不改」的字段补充（详见根目录 README「表结构说明」）：
--      · course   : 增 code（课程业务编码，对应 JSON 的 course.code 与导入入参 courseCode）
--      · kg_node  : 增 is_key（是否重点）、bloom（布卢姆层级）、node_note（备注）
--      · kg_edge  : 增 relation_note（关系备注）
-- ════════════════════════════════════════════════════════════════════════

-- 建库（如已存在则跳过）：编码 utf8mb4
CREATE DATABASE IF NOT EXISTS `wenjin`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE `wenjin`;

-- ───────────────────────────── 组一：用户与权限 ─────────────────────────────

DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`   VARCHAR(64)  NOT NULL                COMMENT '登录名',
    `password`   VARCHAR(128) NOT NULL                COMMENT '密码（加密存储）',
    `real_name`  VARCHAR(64)  DEFAULT NULL            COMMENT '真实姓名',
    `role`       TINYINT      NOT NULL                COMMENT '角色：1=教师, 2=学生',
    `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1=正常, 0=禁用',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                          COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表（教师/学生统一存储）';

-- ───────────────────────────── 组二：课程与图谱（核心） ─────────────────────────────

DROP TABLE IF EXISTS `course`;
CREATE TABLE `course` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '课程ID（即 course_id）',
    `code`        VARCHAR(64)  NOT NULL                COMMENT '课程业务编码（如 52015CC4B4），导入/对接用【PRD外补充字段】',
    `name`        VARCHAR(128) NOT NULL                COMMENT '课程名称（如「软件工程」）',
    `description` VARCHAR(512) DEFAULT NULL            COMMENT '课程描述',
    `teacher_id`  BIGINT       DEFAULT NULL            COMMENT '创建教师（逻辑外键→sys_user.id）',
    `status`      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1=启用, 0=停用',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                          COMMENT '创建时间',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程表（一门课对应一张图谱）';

DROP TABLE IF EXISTS `kg_node`;
CREATE TABLE `kg_node` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部主键（与业务编码 node_code 分离）',
    `course_id`   BIGINT       NOT NULL                COMMENT '所属课程（多课程隔离，逻辑外键→course.id）',
    `node_code`   VARCHAR(32)  NOT NULL                COMMENT '业务ID（如 KT12），导入时关联用',
    `name`        VARCHAR(128) NOT NULL                COMMENT '知识点名称',
    `chapter`     VARCHAR(64)  DEFAULT NULL            COMMENT '所属章节',
    `difficulty`  TINYINT      DEFAULT 1               COMMENT '难度 1–5',
    `is_key`      TINYINT      NOT NULL DEFAULT 0      COMMENT '是否重点：1=是, 0=否【PRD外补充字段】',
    `bloom`       VARCHAR(16)  DEFAULT NULL            COMMENT '布卢姆认知层级（理解/运用/分析…）【PRD外补充字段】',
    `description` VARCHAR(512) DEFAULT NULL            COMMENT '描述',
    `node_note`   VARCHAR(512) DEFAULT NULL            COMMENT '备注（图谱评审说明等）【PRD外补充字段】',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                          COMMENT '创建时间',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_course_code` (`course_id`, `node_code`) COMMENT '同一课程内业务ID唯一（对应导入校验：重复ID检测）',
    KEY `idx_course` (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识点表（图谱节点）';

DROP TABLE IF EXISTS `kg_edge`;
CREATE TABLE `kg_edge` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `course_id`     BIGINT       NOT NULL                COMMENT '所属课程（逻辑外键→course.id）',
    `from_node_id`  BIGINT       NOT NULL                COMMENT '起点知识点（逻辑外键→kg_node.id）',
    `to_node_id`    BIGINT       NOT NULL                COMMENT '终点知识点（逻辑外键→kg_node.id）',
    `relation_type` TINYINT      NOT NULL                COMMENT '关系类型：1=前置, 2=包含, 3=相关, 4=应用',
    `relation_note` VARCHAR(512) DEFAULT NULL            COMMENT '关系备注（评审说明等）【PRD外补充字段】',
    `confidence`    TINYINT      DEFAULT NULL            COMMENT 'AI 置信度 0–100，NULL=既有生效边【阶段六补充】',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                          COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_edge` (`course_id`, `from_node_id`, `to_node_id`, `relation_type`) COMMENT '防重复边',
    KEY `idx_from` (`from_node_id`),
    KEY `idx_to` (`to_node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识点关系表（图谱边）。环检测在应用层执行';

-- ───────────────────────────── 组三：题库与标注 ─────────────────────────────

DROP TABLE IF EXISTS `question`;
CREATE TABLE `question` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '题目ID',
    `course_id`  BIGINT   NOT NULL                COMMENT '所属课程（逻辑外键→course.id）',
    `stem`       TEXT     NOT NULL                COMMENT '题干',
    `type`       TINYINT  NOT NULL                COMMENT '题型：1=单选, 2=多选, 3=判断, 4=简答',
    `difficulty` TINYINT  DEFAULT 1               COMMENT '难度 1–5',
    `answer`     TEXT     DEFAULT NULL            COMMENT '标准答案（客观题存选项标识，简答存参考答案）',
    `analysis`   TEXT     DEFAULT NULL            COMMENT '解析',
    `source`     TINYINT  DEFAULT 1               COMMENT '来源：1=学校题库, 2=AI生成, 3=人工录入',
    `status`     TINYINT  NOT NULL DEFAULT 0      COMMENT '审核状态：0=待审核,1=已通过,2=已驳回【阶段二补充】',
    `confidence` TINYINT  DEFAULT NULL            COMMENT 'AI 置信度 0–100，NULL=既有生效题【阶段六补充】',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP                          COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_course` (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目表（题干、题型、难度、答案）';

DROP TABLE IF EXISTS `question_option`;
CREATE TABLE `question_option` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '选项ID',
    `question_id` BIGINT       NOT NULL                COMMENT '所属题目（逻辑外键→question.id）',
    `option_key`  VARCHAR(8)   NOT NULL                COMMENT '选项标识（A/B/C/D）',
    `option_text` VARCHAR(512) NOT NULL                COMMENT '选项内容',
    `is_correct`  TINYINT      NOT NULL DEFAULT 0      COMMENT '是否正确：1=是, 0=否',
    `point_node_code` VARCHAR(32) DEFAULT NULL          COMMENT '该错误选项暴露的薄弱知识点 node_code（distractor_map，正确项为空）【阶段二补充】',
    PRIMARY KEY (`id`),
    KEY `idx_question` (`question_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目选项表（仅客观题使用）';

DROP TABLE IF EXISTS `question_node`;
CREATE TABLE `question_node` (
    `id`          BIGINT  NOT NULL AUTO_INCREMENT COMMENT '主键',
    `question_id` BIGINT  NOT NULL                COMMENT '题目（逻辑外键→question.id）',
    `node_id`     BIGINT  NOT NULL                COMMENT '关联知识点（逻辑外键→kg_node.id）',
    `weight`      TINYINT NOT NULL DEFAULT 1      COMMENT '主次权重：1=主, 2=次',
    PRIMARY KEY (`id`),
    KEY `idx_question` (`question_id`),
    KEY `idx_node` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目-知识点关联表（含主次权重，认知诊断基础）';

-- ───────────────────────────── 组四：学情数据 ─────────────────────────────

DROP TABLE IF EXISTS `student_mastery`;
CREATE TABLE `student_mastery` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `student_id`    BIGINT       NOT NULL                COMMENT '学生（逻辑外键→sys_user.id）',
    `course_id`     BIGINT       NOT NULL                COMMENT '课程（逻辑外键→course.id）',
    `node_id`       BIGINT       NOT NULL                COMMENT '知识点（逻辑外键→kg_node.id）',
    `mastery_score` DECIMAL(5,2) DEFAULT 0               COMMENT '掌握度分值（0–100）',
    `mastery_level` TINYINT      DEFAULT 0               COMMENT '掌握等级：2=已掌握, 1=薄弱, 0=未学',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_student_course_node` (`student_id`, `course_id`, `node_id`),
    KEY `idx_student_course` (`student_id`, `course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生掌握度表（按 学生×课程×知识点 维度；染色地图直接读 mastery_level）';

DROP TABLE IF EXISTS `mastery_snapshot`;
CREATE TABLE `mastery_snapshot` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `student_id`    BIGINT       NOT NULL                COMMENT '学生（逻辑外键→sys_user.id）',
    `course_id`     BIGINT       NOT NULL                COMMENT '课程（逻辑外键→course.id）',
    `node_id`       BIGINT       NOT NULL                COMMENT '知识点（逻辑外键→kg_node.id）',
    `mastery_score` DECIMAL(5,2) NOT NULL                COMMENT '当时的掌握度分值（0–100）',
    `mastery_level` TINYINT      NOT NULL                COMMENT '当时的掌握等级',
    `snapshot_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照时间',
    PRIMARY KEY (`id`),
    KEY `idx_student_course_node_time` (`student_id`, `course_id`, `node_id`, `snapshot_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='掌握度快照表（只增不改，成长曲线数据来源）';

DROP TABLE IF EXISTS `answer_record`;
CREATE TABLE `answer_record` (
    `id`             BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `student_id`     BIGINT   NOT NULL                COMMENT '学生（逻辑外键→sys_user.id）',
    `course_id`      BIGINT   NOT NULL                COMMENT '课程（逻辑外键→course.id）',
    `question_id`    BIGINT   NOT NULL                COMMENT '题目（逻辑外键→question.id）',
    `student_answer` TEXT     DEFAULT NULL            COMMENT '学生作答内容',
    `is_correct`     TINYINT  DEFAULT NULL            COMMENT '是否正确：1=对, 0=错（简答可由AI判定）',
    `answered_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作答时间',
    PRIMARY KEY (`id`),
    KEY `idx_student_course` (`student_id`, `course_id`),
    KEY `idx_question` (`question_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='答题记录表（每次作答留痕，掌握度计算原始来源）';

DROP TABLE IF EXISTS `learning_path`;
CREATE TABLE `learning_path` (
    `id`             BIGINT   NOT NULL AUTO_INCREMENT COMMENT '路径ID',
    `student_id`     BIGINT   NOT NULL                COMMENT '学生（逻辑外键→sys_user.id）',
    `course_id`      BIGINT   NOT NULL                COMMENT '课程（逻辑外键→course.id）',
    `target_node_id` BIGINT   DEFAULT NULL            COMMENT '学习目标知识点（可空=全局规划，逻辑外键→kg_node.id）',
    `status`         TINYINT  DEFAULT 1               COMMENT '状态：1=当前有效, 0=已失效（重算后旧路径置0）',
    `generated_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    PRIMARY KEY (`id`),
    KEY `idx_student_course` (`student_id`, `course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习路径表（主记录，动态路径留痕）';

DROP TABLE IF EXISTS `learning_path_item`;
CREATE TABLE `learning_path_item` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `path_id`      BIGINT       NOT NULL                COMMENT '所属路径（逻辑外键→learning_path.id）',
    `node_id`      BIGINT       NOT NULL                COMMENT '该步知识点（逻辑外键→kg_node.id）',
    `step_order`   INT          NOT NULL                COMMENT '步骤顺序（拓扑排序结果）',
    `status`       TINYINT      NOT NULL DEFAULT 0      COMMENT '完成状态：0=未学, 1=已完成',
    `completed_at` DATETIME     DEFAULT NULL            COMMENT '完成时间',
    `reason`       VARCHAR(512) DEFAULT NULL            COMMENT '「为什么学这个」的说明（可由AI生成）',
    PRIMARY KEY (`id`),
    KEY `idx_path` (`path_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习路径明细表（路径中的每一步）';

-- ───────────────────────────── 组五：资源（全局共享，不带 course_id） ─────────────────────────────

DROP TABLE IF EXISTS `resource`;
CREATE TABLE `resource` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '资源ID',
    `title`       VARCHAR(256) NOT NULL                COMMENT '资源标题',
    `type`        TINYINT      NOT NULL                COMMENT '类型：1=视频, 2=练习, 3=文章',
    `url`         VARCHAR(512) NOT NULL                COMMENT '资源链接',
    `description` VARCHAR(512) DEFAULT NULL            COMMENT '描述',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习资源表（全局共享，跨课程复用，不带 course_id）';

DROP TABLE IF EXISTS `node_resource`;
CREATE TABLE `node_resource` (
    `id`          BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `node_id`     BIGINT NOT NULL                COMMENT '知识点（逻辑外键→kg_node.id）',
    `resource_id` BIGINT NOT NULL                COMMENT '资源（逻辑外键→resource.id）',
    PRIMARY KEY (`id`),
    KEY `idx_node` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识点-资源关联表（多对多，资源挂接到知识点）';

-- ───────────────────────────── 组六：选课（选课扩展） ─────────────────────────────

DROP TABLE IF EXISTS `student_course`;
CREATE TABLE `student_course` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `student_id` BIGINT   NOT NULL                COMMENT '学生（逻辑外键→sys_user.id）',
    `course_id`  BIGINT   NOT NULL                COMMENT '课程（逻辑外键→course.id）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '选课时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_student_course` (`student_id`, `course_id`),
    KEY `idx_course` (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生选课表（学生×课程 多对多）';

-- ───────────────────────────── 组X：AI 学习伴侣（阶段五新增） ─────────────────────────────

DROP TABLE IF EXISTS `companion_conversation`;
CREATE TABLE `companion_conversation` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    `student_id` BIGINT       NOT NULL                COMMENT '学生（逻辑外键→sys_user.id）',
    `course_id`  BIGINT       NOT NULL                COMMENT '课程（逻辑外键→course.id）',
    `title`      VARCHAR(128) DEFAULT NULL            COMMENT '会话标题（首问截断/节点名前缀）',
    `node_code`  VARCHAR(64)  DEFAULT NULL            COMMENT '发起时的图谱节点上下文（可空）',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                          COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_student_course` (`student_id`, `course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 学习伴侣会话';

DROP TABLE IF EXISTS `companion_message`;
CREATE TABLE `companion_message` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `conversation_id` BIGINT   NOT NULL                COMMENT '所属会话（逻辑外键→companion_conversation.id）',
    `role`            TINYINT  NOT NULL                COMMENT '角色：1=user, 2=ai',
    `content`         TEXT     NOT NULL                COMMENT '消息正文',
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_conversation_time` (`conversation_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='伴侣会话消息';

DROP TABLE IF EXISTS `extraction_review`;
CREATE TABLE `extraction_review` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    `course_code`         VARCHAR(64)  NOT NULL                COMMENT '课程业务编码',
    `course_id`           BIGINT       DEFAULT NULL            COMMENT '课程(逻辑外键→course.id)',
    `node_ai_count`       INT          NOT NULL DEFAULT 0      COMMENT '节点:AI产出数',
    `node_kept_count`     INT          NOT NULL DEFAULT 0      COMMENT '节点:保留数',
    `node_deleted_count`  INT          NOT NULL DEFAULT 0      COMMENT '节点:删除数',
    `node_added_count`    INT          NOT NULL DEFAULT 0      COMMENT '节点:新增数',
    `node_modified_count` INT          NOT NULL DEFAULT 0      COMMENT '节点:修改数',
    `node_final_count`    INT          NOT NULL DEFAULT 0      COMMENT '节点:最终总数',
    `node_recall`         DECIMAL(5,4) DEFAULT NULL            COMMENT '节点召回率 kept/final',
    `node_precision`      DECIMAL(5,4) DEFAULT NULL            COMMENT '节点精确率 kept/ai',
    `edge_ai_count`       INT          NOT NULL DEFAULT 0      COMMENT '边:AI产出数',
    `edge_kept_count`     INT          NOT NULL DEFAULT 0      COMMENT '边:保留数',
    `edge_deleted_count`  INT          NOT NULL DEFAULT 0      COMMENT '边:删除数',
    `edge_added_count`    INT          NOT NULL DEFAULT 0      COMMENT '边:新增数',
    `edge_modified_count` INT          NOT NULL DEFAULT 0      COMMENT '边:修改数',
    `edge_final_count`    INT          NOT NULL DEFAULT 0      COMMENT '边:最终总数',
    `edge_recall`         DECIMAL(5,4) DEFAULT NULL            COMMENT '边召回率',
    `edge_precision`      DECIMAL(5,4) DEFAULT NULL            COMMENT '边精确率',
    `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_course_time` (`course_code`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程标准抽取审核指标记录';
