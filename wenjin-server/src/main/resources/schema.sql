-- ════════════════════════════════════════════════════════════════════════
--  问津 · 知识图谱驱动的个性化学习导航系统
--  本地空库重建快照（当前最终 DDL，含 V3–V22；共 43 张业务表）
--
--  ⚠ 仅本地空库 / 会删数据 / 生产禁用
--    · 本脚本含 DROP TABLE IF EXISTS，会清空目标库业务表数据。
--    · 生产与 CI 一律走 Flyway：classpath:db/migration（V1 baseline + 增量至 V22）。
--    · 新增版本化迁移（Vn）后，必须把最终 DDL 同步回本文件，保持本地快照一致。
--    · 应用启动默认 spring.flyway.enabled=false（WENJIN_FLYWAY_ENABLED），
--      普通应用账号只有 DML；发布流水线用 migrator 凭据单独跑迁移作业。
--
--  使用说明（仅本地）：
--    1. 本脚本可重复执行（FOREIGN_KEY_CHECKS=0 下 DROP IF EXISTS 再 CREATE）。
--    2. 字符集统一 utf8mb4，存储引擎 InnoDB。
--    3. 所有表、字段均带中文 COMMENT。
--    4. V9 起建立命名物理外键与 sys_user.role CHECK；本地快照与 Flyway 最终态对齐。
--    5. V10 增 agent_run / agent_step（无物理 FK，与迁移脚本一致）。
--    6. V11 增 eval_scenario / eval_case_result（评测场景与结果，无物理 FK）。
--    7. V12 增 student_memory；companion_conversation 增 distilled_through / summary_origin。
--    8. V13 增 doc_source / doc_chunk / chunk_node_map（M8 RAG；FULLTEXT ngram）。
--    9. V14 增 agent_plan / agent_plan_task / tool_effect_key（M10 规划）。
--   10. V15 增 agent_report（M11 多智能体周报 / 抽查，可选）。
--   11. V16 增 audit_log / login_log（管理后台审计与监控，无物理 FK）；sys_user 增 token_valid_after。
--   12. V17 增 clazz / clazz_student / clazz_course（管理后台班级；含物理 FK，删班级级联删成员与课程关联行）。
--   13. V18 增 sys_config（管理后台系统配置覆盖层；secret 存 AES-GCM 密文，无物理 FK）。
--   14. V19 增 review_pin（今日复习置顶）；V20 增 sys_user.email（注册邮箱，可空唯一）。
--   15. V21 增 answer_record.idx_student_course_time（错因辅导按时间取最近错题）。
--   16. V22 增 companion_hint_log（主动陪伴提示条展示/关闭日志，无物理 FK）。
--   17. V23 增 doc_chunk_embedding（RAG 切块向量，无物理 FK）。
--   18. V24 增 question.starter_code / code_language 与 question_code_case（编程题题型，无物理 FK）。
--   19. 演示种子数据见 data.sql（本地：schema.sql + data.sql；生产勿跑 data.sql）。
-- ════════════════════════════════════════════════════════════════════════

CREATE DATABASE IF NOT EXISTS `wenjin`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE `wenjin`;

SET FOREIGN_KEY_CHECKS = 0;

-- ───────────────────────────── 组一：用户与权限 ─────────────────────────────

DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`   VARCHAR(64)  NOT NULL                COMMENT '登录名',
    `password`   VARCHAR(128) NOT NULL                COMMENT '密码（加密存储）',
    `real_name`  VARCHAR(64)  DEFAULT NULL            COMMENT '真实姓名',
    `email`      VARCHAR(255) DEFAULT NULL            COMMENT '注册邮箱【V20】',
    `role`       TINYINT      NOT NULL                COMMENT '角色：1=教师, 2=学生, 3=管理员',
    `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1=正常, 0=禁用',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                          COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `token_valid_after` DATETIME(3) NULL                 COMMENT '早于此刻签发的令牌一律无效（重置密码时写入）【V16】',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_sys_user_email` (`email`),
    CONSTRAINT `chk_sys_user_role` CHECK (`role` IN (1, 2, 3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表（教师/学生统一存储）';

-- ───────────────────────────── 组二：课程与图谱（核心） ─────────────────────────────

DROP TABLE IF EXISTS `course`;
CREATE TABLE `course` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '课程ID（即 course_id）',
    `code`        VARCHAR(64)  NOT NULL                COMMENT '课程业务编码（如 52015CC4B4），导入/对接用【PRD外补充字段】',
    `name`        VARCHAR(128) NOT NULL                COMMENT '课程名称（如「软件工程」）',
    `description` VARCHAR(512) DEFAULT NULL            COMMENT '课程描述',
    `teacher_id`  BIGINT       NOT NULL                COMMENT '创建教师（物理外键→sys_user.id）【V9】',
    `status`      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1=启用, 0=停用',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                          COMMENT '创建时间',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `archived_at` DATETIME     DEFAULT NULL            COMMENT '软归档时间；NULL=生效【V4】',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_course_owner_active` (`teacher_id`, `archived_at`, `id`),
    CONSTRAINT `fk_course_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程表（一门课对应一张图谱）';

DROP TABLE IF EXISTS `kg_node`;
CREATE TABLE `kg_node` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部主键（与业务编码 node_code 分离）',
    `course_id`   BIGINT       NOT NULL                COMMENT '所属课程（多课程隔离，物理外键→course.id）',
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
    `archived_at` DATETIME     DEFAULT NULL            COMMENT '软归档时间；NULL=生效【V4】',
    `archived_by_course` TINYINT NOT NULL DEFAULT 0 COMMENT '1=由课程归档级联；0=独立归档【V4】',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_course_code` (`course_id`, `node_code`) COMMENT '同一课程内业务ID唯一（对应导入校验：重复ID检测）',
    KEY `idx_course` (`course_id`),
    KEY `idx_node_course_active` (`course_id`, `archived_at`, `id`),
    CONSTRAINT `chk_node_course_archive_marker` CHECK (`archived_by_course` IN (0, 1) AND (`archived_by_course` = 0 OR `archived_at` IS NOT NULL)),
    CONSTRAINT `fk_node_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识点表（图谱节点）';

DROP TABLE IF EXISTS `kg_edge`;
CREATE TABLE `kg_edge` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `course_id`     BIGINT       NOT NULL                COMMENT '所属课程（物理外键→course.id）',
    `from_node_id`  BIGINT       NOT NULL                COMMENT '起点知识点（物理外键→kg_node.id）',
    `to_node_id`    BIGINT       NOT NULL                COMMENT '终点知识点（物理外键→kg_node.id）',
    `relation_type` TINYINT      NOT NULL                COMMENT '关系类型：1=前置, 2=包含, 3=相关, 4=应用',
    `relation_note` VARCHAR(512) DEFAULT NULL            COMMENT '关系备注（评审说明等）【PRD外补充字段】',
    `confidence`    TINYINT      DEFAULT NULL            COMMENT 'AI 置信度 0–100，NULL=既有生效边【阶段六补充】',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                          COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `archived_at`   DATETIME     DEFAULT NULL            COMMENT '软归档时间；NULL=生效【V4】',
    `archived_by_course` TINYINT NOT NULL DEFAULT 0 COMMENT '1=由课程归档级联；0=独立归档【V4】',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_edge` (`course_id`, `from_node_id`, `to_node_id`, `relation_type`) COMMENT '防重复边',
    KEY `idx_from` (`from_node_id`),
    KEY `idx_to` (`to_node_id`),
    KEY `idx_edge_course_active` (`course_id`, `archived_at`, `id`),
    CONSTRAINT `chk_edge_course_archive_marker` CHECK (`archived_by_course` IN (0, 1) AND (`archived_by_course` = 0 OR `archived_at` IS NOT NULL)),
    CONSTRAINT `fk_edge_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
    CONSTRAINT `fk_edge_from_node` FOREIGN KEY (`from_node_id`) REFERENCES `kg_node` (`id`),
    CONSTRAINT `fk_edge_to_node` FOREIGN KEY (`to_node_id`) REFERENCES `kg_node` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识点关系表（图谱边）。环检测在应用层执行';

-- ───────────────────────────── 组三：题库与标注 ─────────────────────────────

DROP TABLE IF EXISTS `question`;
CREATE TABLE `question` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '题目ID',
    `course_id`  BIGINT   NOT NULL                COMMENT '所属课程（物理外键→course.id）',
    `stem`       TEXT     NOT NULL                COMMENT '题干',
    `stem_hash`  CHAR(64) NOT NULL                COMMENT '题干 SHA-256 十六进制指纹（trim 后，与应用 StemHash 一致）【V6/V9】',
    `type`       TINYINT  NOT NULL                COMMENT '题型：1=单选, 2=多选, 3=判断, 4=简答, 5=编程【V24】',
    `difficulty` TINYINT  DEFAULT 1               COMMENT '难度 1–5',
    `answer`     TEXT     DEFAULT NULL            COMMENT '标准答案（客观题存选项标识，简答存参考答案）',
    `analysis`   TEXT     DEFAULT NULL            COMMENT '解析',
    `starter_code`  TEXT        NULL              COMMENT '编程题起始代码（可空；仅 type=5）【V24】',
    `code_language` VARCHAR(16) NULL DEFAULT 'java' COMMENT '编程题语言（v1 仅 java）【V24】',
    `source`     TINYINT  DEFAULT 1               COMMENT '来源：1=学校题库, 2=AI生成, 3=人工录入',
    `status`     TINYINT  NOT NULL DEFAULT 0      COMMENT '审核状态：0=待审核,1=已通过,2=已驳回【阶段二补充】',
    `confidence` TINYINT  DEFAULT NULL            COMMENT 'AI 置信度 0–100，NULL=既有生效题【阶段六补充】',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP                          COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_question_id_course` (`id`, `course_id`),
    UNIQUE KEY `uk_question_course_stem_hash` (`course_id`, `stem_hash`),
    KEY `idx_course` (`course_id`),
    CONSTRAINT `fk_question_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目表（题干、题型、难度、答案）';

DROP TABLE IF EXISTS `question_option`;
CREATE TABLE `question_option` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '选项ID',
    `question_id` BIGINT       NOT NULL                COMMENT '所属题目（物理外键→question.id）',
    `option_key`  VARCHAR(8)   NOT NULL                COMMENT '选项标识（A/B/C/D）',
    `option_text` VARCHAR(512) NOT NULL                COMMENT '选项内容',
    `is_correct`  TINYINT      NOT NULL DEFAULT 0      COMMENT '是否正确：1=是, 0=否',
    `point_node_code` VARCHAR(32) DEFAULT NULL          COMMENT '该错误选项暴露的薄弱知识点 node_code（distractor_map，正确项为空）【阶段二补充】',
    PRIMARY KEY (`id`),
    KEY `idx_question` (`question_id`),
    CONSTRAINT `fk_option_question` FOREIGN KEY (`question_id`) REFERENCES `question` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目选项表（仅客观题使用）';

DROP TABLE IF EXISTS `question_node`;
CREATE TABLE `question_node` (
    `id`          BIGINT  NOT NULL AUTO_INCREMENT COMMENT '主键',
    `question_id` BIGINT  NOT NULL                COMMENT '题目（物理外键→question.id）',
    `node_id`     BIGINT  NOT NULL                COMMENT '关联知识点（物理外键→kg_node.id）',
    `weight`      TINYINT NOT NULL DEFAULT 1      COMMENT '主次权重：1=主, 2=次',
    PRIMARY KEY (`id`),
    KEY `idx_question` (`question_id`),
    KEY `idx_node` (`node_id`),
    CONSTRAINT `fk_qn_question` FOREIGN KEY (`question_id`) REFERENCES `question` (`id`),
    CONSTRAINT `fk_qn_node` FOREIGN KEY (`node_id`) REFERENCES `kg_node` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目-知识点关联表（含主次权重，认知诊断基础）';

-- ───────────────────────────── 组四：学情数据 ─────────────────────────────

DROP TABLE IF EXISTS `student_mastery`;
CREATE TABLE `student_mastery` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `student_id`    BIGINT       NOT NULL                COMMENT '学生（物理外键→sys_user.id）',
    `course_id`     BIGINT       NOT NULL                COMMENT '课程（物理外键→course.id）',
    `node_id`       BIGINT       NOT NULL                COMMENT '知识点（物理外键→kg_node.id）',
    `mastery_score` DECIMAL(5,2) DEFAULT 0               COMMENT '掌握度分值（0–100）',
    `mastery_level` TINYINT      DEFAULT 0               COMMENT '掌握等级：2=已掌握, 1=薄弱, 0=未学',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_student_course_node` (`student_id`, `course_id`, `node_id`),
    KEY `idx_student_course` (`student_id`, `course_id`),
    CONSTRAINT `fk_mastery_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    CONSTRAINT `fk_mastery_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
    CONSTRAINT `fk_mastery_node` FOREIGN KEY (`node_id`) REFERENCES `kg_node` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生掌握度表（按 学生×课程×知识点 维度；染色地图直接读 mastery_level）';

DROP TABLE IF EXISTS `mastery_snapshot`;
CREATE TABLE `mastery_snapshot` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `student_id`    BIGINT       NOT NULL                COMMENT '学生（物理外键→sys_user.id）',
    `course_id`     BIGINT       NOT NULL                COMMENT '课程（物理外键→course.id）',
    `node_id`       BIGINT       NOT NULL                COMMENT '知识点（物理外键→kg_node.id）',
    `mastery_score` DECIMAL(5,2) NOT NULL                COMMENT '当时的掌握度分值（0–100）',
    `mastery_level` TINYINT      NOT NULL                COMMENT '当时的掌握等级',
    `snapshot_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照时间',
    PRIMARY KEY (`id`),
    KEY `idx_student_course_node_time` (`student_id`, `course_id`, `node_id`, `snapshot_at`),
    CONSTRAINT `fk_snapshot_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    CONSTRAINT `fk_snapshot_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
    CONSTRAINT `fk_snapshot_node` FOREIGN KEY (`node_id`) REFERENCES `kg_node` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='掌握度快照表（只增不改，成长曲线数据来源）';

DROP TABLE IF EXISTS `learning_path`;
CREATE TABLE `learning_path` (
    `id`             BIGINT   NOT NULL AUTO_INCREMENT COMMENT '路径ID',
    `student_id`     BIGINT   NOT NULL                COMMENT '学生（物理外键→sys_user.id）',
    `course_id`      BIGINT   NOT NULL                COMMENT '课程（物理外键→course.id）',
    `target_node_id` BIGINT   DEFAULT NULL            COMMENT '学习目标知识点（可空=全局规划，物理外键→kg_node.id）',
    `status`         TINYINT  DEFAULT 1               COMMENT '状态：1=当前有效, 0=已失效（重算后旧路径置0）',
    `generated_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    `active_guard`   TINYINT  GENERATED ALWAYS AS (IF(`status` = 1, 1, NULL)) STORED
        COMMENT '生成列：status=1 时为 1，否则 NULL；配合唯一约束保证至多一条有效路径【V5】',
    PRIMARY KEY (`id`),
    KEY `idx_student_course` (`student_id`, `course_id`),
    UNIQUE KEY `uk_learning_path_active` (`student_id`, `course_id`, `active_guard`),
    CONSTRAINT `fk_path_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    CONSTRAINT `fk_path_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
    CONSTRAINT `fk_path_target_node` FOREIGN KEY (`target_node_id`) REFERENCES `kg_node` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习路径表（主记录，动态路径留痕）';

DROP TABLE IF EXISTS `learning_path_item`;
CREATE TABLE `learning_path_item` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `path_id`      BIGINT       NOT NULL                COMMENT '所属路径（物理外键→learning_path.id）',
    `node_id`      BIGINT       NOT NULL                COMMENT '该步知识点（物理外键→kg_node.id）',
    `step_order`   INT          NOT NULL                COMMENT '步骤顺序（拓扑排序结果）',
    `status`       TINYINT      NOT NULL DEFAULT 0      COMMENT '完成状态：0=未学, 1=已完成',
    `completed_at` DATETIME     DEFAULT NULL            COMMENT '完成时间',
    `reason`       VARCHAR(512) DEFAULT NULL            COMMENT '「为什么学这个」的说明（可由AI生成）',
    PRIMARY KEY (`id`),
    KEY `idx_path` (`path_id`),
    UNIQUE KEY `uk_path_step_order` (`path_id`, `step_order`),
    UNIQUE KEY `uk_path_node` (`path_id`, `node_id`),
    CONSTRAINT `fk_path_item_path` FOREIGN KEY (`path_id`) REFERENCES `learning_path` (`id`),
    CONSTRAINT `fk_path_item_node` FOREIGN KEY (`node_id`) REFERENCES `kg_node` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习路径明细表（路径中的每一步）';

DROP TABLE IF EXISTS `practice_session`;
CREATE TABLE `practice_session` (
    `id`           BIGINT   NOT NULL AUTO_INCREMENT COMMENT '练习会话ID',
    `student_id`   BIGINT   NOT NULL COMMENT '学生（物理外键→sys_user.id）',
    `course_id`    BIGINT   NOT NULL COMMENT '课程（物理外键→course.id）',
    `node_id`      BIGINT   NOT NULL COMMENT '目标知识点（物理外键→kg_node.id）',
    `path_item_id` BIGINT   DEFAULT NULL COMMENT '来源路径步骤（可空=自由练习，物理外键→learning_path_item.id）',
    `question_ids` VARCHAR(512) NOT NULL COMMENT '本会话题目ID列表，逗号分隔（组卷即冻结，防提交时偷换题目）',
    `status`       TINYINT  NOT NULL DEFAULT 0 COMMENT '状态：0=进行中, 1=已提交',
    `created_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `submitted_at` DATETIME DEFAULT NULL COMMENT '提交时间',
    PRIMARY KEY (`id`),
    KEY `idx_student_course_node` (`student_id`, `course_id`, `node_id`),
    CONSTRAINT `fk_practice_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    CONSTRAINT `fk_practice_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
    CONSTRAINT `fk_practice_node` FOREIGN KEY (`node_id`) REFERENCES `kg_node` (`id`),
    CONSTRAINT `fk_practice_path_item` FOREIGN KEY (`path_item_id`) REFERENCES `learning_path_item` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节点练习会话（M1 练习闭环）';

DROP TABLE IF EXISTS `adaptive_diag_session`;
CREATE TABLE `adaptive_diag_session` (
    `id`          BIGINT     NOT NULL AUTO_INCREMENT COMMENT '自适应诊断会话ID',
    `student_id`  BIGINT     NOT NULL COMMENT '学生（物理外键→sys_user.id）',
    `course_id`   BIGINT     NOT NULL COMMENT '课程（物理外键→course.id）',
    `state_json`  MEDIUMTEXT NOT NULL COMMENT '会话状态快照：节点状态机/已用预算/当前探针（服务端为唯一事实源）',
    `status`      TINYINT    NOT NULL DEFAULT 0 COMMENT '状态：0=进行中, 1=已完成, 2=已放弃',
    `created_at`  DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `finished_at` DATETIME   DEFAULT NULL COMMENT '完成/放弃时间',
    PRIMARY KEY (`id`),
    KEY `idx_student_course` (`student_id`, `course_id`),
    CONSTRAINT `fk_adaptive_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    CONSTRAINT `fk_adaptive_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自适应诊断会话（M2 图引导自适应诊断）';

DROP TABLE IF EXISTS `diagnostic_session`;
CREATE TABLE `diagnostic_session` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '固定诊断会话ID',
    `token`       CHAR(36)     NOT NULL                COMMENT '会话令牌 UUID（客户端持有）',
    `student_id`  BIGINT       NOT NULL                COMMENT '学生（物理外键→sys_user.id）',
    `course_id`   BIGINT       NOT NULL                COMMENT '课程（物理外键→course.id）',
    `status`      TINYINT      NOT NULL DEFAULT 0      COMMENT '状态：0=进行中, 1=已完成',
    `result_json` MEDIUMTEXT   DEFAULT NULL            COMMENT '完成后幂等重放用的判分结果 JSON',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `finished_at` DATETIME     DEFAULT NULL            COMMENT '完成时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token` (`token`),
    KEY `idx_student_course` (`student_id`, `course_id`),
    CONSTRAINT `fk_diag_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    CONSTRAINT `fk_diag_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='固定卷诊断会话（组卷即冻结题集）【V3】';

DROP TABLE IF EXISTS `diagnostic_session_question`;
CREATE TABLE `diagnostic_session_question` (
    `id`          BIGINT  NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`  BIGINT  NOT NULL                COMMENT '会话（物理外键→diagnostic_session.id）',
    `question_id` BIGINT  NOT NULL                COMMENT '题目（物理外键→question.id）',
    `course_id`   BIGINT  NOT NULL                COMMENT '课程（与 question.course_id 一致）',
    `ordinal`     INT     NOT NULL                COMMENT '卷内顺序（0-based）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_question` (`session_id`, `question_id`),
    KEY `idx_session_ordinal` (`session_id`, `ordinal`),
    CONSTRAINT `fk_dsq_session`
        FOREIGN KEY (`session_id`) REFERENCES `diagnostic_session` (`id`),
    CONSTRAINT `fk_dsq_question_course`
        FOREIGN KEY (`question_id`, `course_id`) REFERENCES `question` (`id`, `course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='固定卷诊断会话冻结题目【V3】';

DROP TABLE IF EXISTS `answer_record`;
CREATE TABLE `answer_record` (
    `id`             BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `student_id`     BIGINT   NOT NULL                COMMENT '学生（物理外键→sys_user.id）',
    `course_id`      BIGINT   NOT NULL                COMMENT '课程（物理外键→course.id）',
    `question_id`    BIGINT   NOT NULL                COMMENT '题目（物理外键→question.id）',
    `student_answer` TEXT     DEFAULT NULL            COMMENT '学生作答内容',
    `is_correct`     TINYINT  DEFAULT NULL            COMMENT '是否正确：1=对, 0=错（简答可由AI判定）',
    `answered_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作答时间',
    `scene`          TINYINT  NOT NULL DEFAULT 1      COMMENT '作答场景：1=诊断, 2=节点练习【M1补充】',
    `session_id`     BIGINT   DEFAULT NULL            COMMENT '按 scene 解释的逻辑兼容会话ID；可指 adaptive_diag_session 或 practice_session，不设单表物理外键',
    `fixed_diagnostic_session_id` BIGINT DEFAULT NULL COMMENT '固定诊断会话ID（scene=1 固定卷时非空，物理外键→diagnostic_session.id）【V3】',
    PRIMARY KEY (`id`),
    KEY `idx_student_course` (`student_id`, `course_id`),
    KEY `idx_student_course_time` (`student_id`, `course_id`, `answered_at`) COMMENT '【V21】错因辅导按时间取最近错题',
    KEY `idx_question` (`question_id`),
    UNIQUE KEY `uk_fixed_diag_session_question` (`fixed_diagnostic_session_id`, `question_id`),
    CONSTRAINT `fk_answer_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    CONSTRAINT `fk_answer_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
    CONSTRAINT `fk_answer_question` FOREIGN KEY (`question_id`) REFERENCES `question` (`id`),
    CONSTRAINT `fk_answer_fixed_diag` FOREIGN KEY (`fixed_diagnostic_session_id`) REFERENCES `diagnostic_session` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='答题记录表（每次作答留痕，掌握度计算原始来源）';

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
    `node_id`     BIGINT NOT NULL                COMMENT '知识点（物理外键→kg_node.id）',
    `resource_id` BIGINT NOT NULL                COMMENT '资源（物理外键→resource.id）',
    PRIMARY KEY (`id`),
    KEY `idx_node` (`node_id`),
    CONSTRAINT `fk_node_resource_node` FOREIGN KEY (`node_id`) REFERENCES `kg_node` (`id`),
    CONSTRAINT `fk_node_resource_resource` FOREIGN KEY (`resource_id`) REFERENCES `resource` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识点-资源关联表（多对多，资源挂接到知识点）';

-- ───────────────────────────── 组六：选课（选课扩展） ─────────────────────────────

DROP TABLE IF EXISTS `student_course`;
CREATE TABLE `student_course` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `student_id`      BIGINT   NOT NULL                COMMENT '学生（物理外键→sys_user.id）',
    `course_id`       BIGINT   NOT NULL                COMMENT '课程（物理外键→course.id）',
    `current_path_id` BIGINT   DEFAULT NULL            COMMENT '当前有效学习路径（物理外键→learning_path.id）【V5】',
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '选课时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_student_course` (`student_id`, `course_id`),
    KEY `idx_course` (`course_id`),
    KEY `idx_current_path` (`current_path_id`),
    CONSTRAINT `fk_sc_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    CONSTRAINT `fk_sc_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
    CONSTRAINT `fk_sc_current_path` FOREIGN KEY (`current_path_id`) REFERENCES `learning_path` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生选课表（学生×课程 多对多）';

-- ───────────────────────────── 组X：AI 学习伴侣 ─────────────────────────────

DROP TABLE IF EXISTS `companion_conversation`;
CREATE TABLE `companion_conversation` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    `student_id` BIGINT       NOT NULL                COMMENT '学生（物理外键→sys_user.id）',
    `course_id`  BIGINT       NOT NULL                COMMENT '课程（物理外键→course.id）',
    `title`      VARCHAR(128) DEFAULT NULL            COMMENT '会话标题（首问截断/节点名前缀）',
    `node_code`  VARCHAR(64)  DEFAULT NULL            COMMENT '发起时的图谱节点上下文（可空）',
    `summary_text` MEDIUMTEXT NULL                    COMMENT '历史消息滚动摘要（送入模型）【V8】',
    `summary_origin` TINYINT NOT NULL DEFAULT 0       COMMENT '0=DETERMINISTIC mergeSummary,1=LLM SUMMARY_REFRESH【V12】',
    `summary_through_message_id` BIGINT NULL          COMMENT '摘要覆盖到的最后一条消息 id（含）【V8】',
    `distilled_through_message_id` BIGINT NULL        COMMENT '已蒸馏到的最后一条消息 id（含）【V12】',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                          COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_student_course` (`student_id`, `course_id`),
    CONSTRAINT `fk_companion_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    CONSTRAINT `fk_companion_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 学习伴侣会话';

DROP TABLE IF EXISTS `student_memory`;
CREATE TABLE `student_memory` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '记忆ID',
    `student_id`          BIGINT        NOT NULL COMMENT '学生（逻辑外键→sys_user.id）',
    `course_id`           BIGINT        NOT NULL COMMENT '课程（逻辑外键→course.id）',
    `kind`                TINYINT       NOT NULL COMMENT '0=EPISODIC,1=SEMANTIC,2=PROCEDURAL',
    `content`             VARCHAR(500)  NOT NULL COMMENT '记忆正文',
    `node_code`           VARCHAR(32)   NULL COMMENT '关联知识点 node_code（可空）',
    `source_run_id`       BIGINT        NULL COMMENT '来源 agent_run.id（可空）',
    `salience`            DECIMAL(6,4)  NOT NULL DEFAULT 1.0000 COMMENT '基础显著度',
    `last_reinforced_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近强化时间',
    `status`              TINYINT       NOT NULL DEFAULT 1 COMMENT '0=ARCHIVED,1=ACTIVE',
    `created_at`          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_mem_student` (`student_id`, `course_id`, `kind`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生长期记忆（M7）【V12】';

DROP TABLE IF EXISTS `chunk_node_map`;
DROP TABLE IF EXISTS `doc_chunk`;
DROP TABLE IF EXISTS `doc_source`;
CREATE TABLE `doc_source` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '来源ID',
    `course_id`  BIGINT       NOT NULL COMMENT '课程（逻辑外键→course.id）',
    `title`      VARCHAR(200) NOT NULL COMMENT '语料标题',
    `kind`       TINYINT      NOT NULL COMMENT '0=COURSEWARE,1=TEXTBOOK,2=NODE_REF',
    `file_hash`  CHAR(64)     NULL COMMENT '内容寻址 hash（复用 AiJobPayloadStore）',
    `status`     TINYINT      NOT NULL DEFAULT 0 COMMENT '0=INGESTING,1=READY,2=FAILED,3=ARCHIVED',
    `created_by` BIGINT       NOT NULL COMMENT '创建者（逻辑外键→sys_user.id）',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_source_course` (`course_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='语料来源（M8）【V13】';

CREATE TABLE `doc_chunk` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '切块ID',
    `source_id`  BIGINT       NOT NULL COMMENT '来源（逻辑外键→doc_source.id）',
    `seq`        INT          NOT NULL COMMENT '块序号（同 source 内从 0 递增）',
    `content`    TEXT         NOT NULL COMMENT '切块正文',
    `char_count` INT          NOT NULL COMMENT '字符数',
    `meta_json`  VARCHAR(500) NULL COMMENT '标题路径/页码等元数据 JSON',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk_seq` (`source_id`, `seq`),
    FULLTEXT KEY `ft_chunk_content` (`content`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='语料切块（M8）【V13】';

CREATE TABLE `chunk_node_map` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '映射ID',
    `chunk_id`      BIGINT       NOT NULL COMMENT '切块（逻辑外键→doc_chunk.id）',
    `course_id`     BIGINT       NOT NULL COMMENT '课程（逻辑外键→course.id）',
    `node_code`     VARCHAR(32)  NOT NULL COMMENT '知识点 node_code',
    `confidence`    DECIMAL(4,3) NOT NULL DEFAULT 1.000 COMMENT '映射置信度',
    `review_status` TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING,1=APPROVED,2=REJECTED',
    `reviewed_by`   BIGINT       NULL COMMENT '审核人（可空）',
    `reviewed_at`   DATETIME     NULL COMMENT '审核时间（可空）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_map_chunk_node` (`chunk_id`, `node_code`),
    KEY `idx_map_node` (`course_id`, `node_code`, `review_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='chunk↔节点映射（M8）【V13】';

DROP TABLE IF EXISTS `companion_message`;
CREATE TABLE `companion_message` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `conversation_id` BIGINT   NOT NULL                COMMENT '所属会话（物理外键→companion_conversation.id）',
    `role`            TINYINT  NOT NULL                COMMENT '角色：1=user, 2=ai',
    `content`         TEXT     NOT NULL                COMMENT '消息正文',
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_conversation_time` (`conversation_id`, `created_at`),
    CONSTRAINT `fk_companion_msg_conv` FOREIGN KEY (`conversation_id`) REFERENCES `companion_conversation` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='伴侣会话消息';

DROP TABLE IF EXISTS `extraction_review`;
CREATE TABLE `extraction_review` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    `course_code`         VARCHAR(64)  NOT NULL                COMMENT '课程业务编码',
    `course_id`           BIGINT       DEFAULT NULL            COMMENT '课程(物理外键→course.id)',
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
    KEY `idx_course_time` (`course_code`, `created_at`),
    CONSTRAINT `fk_extraction_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程标准抽取审核指标记录';

DROP TABLE IF EXISTS `ai_job`;
CREATE TABLE `ai_job` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '作业ID',
    `kind`         VARCHAR(32)  NOT NULL                COMMENT '作业类型',
    `owner_id`     BIGINT       NOT NULL                COMMENT '发起教师（物理外键→sys_user.id）',
    `course_id`    BIGINT       NOT NULL                COMMENT '所属课程（物理外键→course.id）',
    `request_key`  VARCHAR(64)  NOT NULL                COMMENT '幂等键（Idempotency-Key）',
    `status`       TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING,1=RUNNING,2=SUCCEEDED,3=FAILED,4=CANCELLED',
    `progress`     INT          NOT NULL DEFAULT 0      COMMENT '进度 0–100',
    `attempts`     INT          NOT NULL DEFAULT 0      COMMENT '领取次数',
    `request_json` MEDIUMTEXT   NOT NULL                COMMENT '请求载荷 JSON',
    `result_json`  MEDIUMTEXT   NULL                    COMMENT '成功结果 JSON',
    `error_code`   VARCHAR(64)  NULL                    COMMENT '稳定错误码（不落敏感明细）',
    `cancel_requested_at` DATETIME NULL                 COMMENT '持久化取消请求时间',
    `lease_token`  CHAR(36)     NULL                    COMMENT 'worker 租约 UUID',
    `lease_until`  DATETIME     NULL                    COMMENT 'worker 租约到期时间',
    `heartbeat_at` DATETIME     NULL                    COMMENT 'worker 最近心跳时间',
    `effect_started_at` DATETIME NULL                   COMMENT '业务副作用边界开始时间',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `started_at`   DATETIME     NULL                    COMMENT '最近一次开始执行时间',
    `finished_at`  DATETIME     NULL                    COMMENT '结束时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_job_request` (`owner_id`, `kind`, `request_key`),
    KEY `idx_ai_job_claim` (`status`, `created_at`),
    KEY `idx_ai_job_lease` (`status`, `lease_until`),
    CONSTRAINT `fk_ai_job_owner` FOREIGN KEY (`owner_id`) REFERENCES `sys_user` (`id`),
    CONSTRAINT `fk_ai_job_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 异步写作业（可恢复）【V7】';

-- ───────────────────────────── 组：Agent 执行轨迹【V10】 ─────────────────────────────

DROP TABLE IF EXISTS `agent_step`;
DROP TABLE IF EXISTS `agent_run`;
CREATE TABLE `agent_run` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '轨迹 run ID',
    `request_id`          VARCHAR(64)  NULL COMMENT '关联 RequestIdFilter',
    `student_id`          BIGINT       NOT NULL COMMENT '学生用户 ID',
    `course_id`           BIGINT       NOT NULL COMMENT '课程 ID',
    `conversation_id`     BIGINT       NULL COMMENT '关联伴侣会话（可空）',
    `kind`                VARCHAR(32)  NOT NULL COMMENT 'COMPANION_CHAT/DIAGNOSTIC/PLAN_GENERATE/PLAN_REPLAN/...',
    `status`              TINYINT      NOT NULL DEFAULT 0 COMMENT '0=RUNNING,1=SUCCEEDED,2=FAILED,3=DEGRADED,4=CANCELLED',
    `model`               VARCHAR(64)  NULL COMMENT '模型标识',
    `input_digest`        VARCHAR(200) NULL COMMENT '首条输入前 200 字，非全文',
    `tokens_in`           INT          NOT NULL DEFAULT 0 COMMENT '输入 token 数',
    `tokens_out`          INT          NOT NULL DEFAULT 0 COMMENT '输出 token 数',
    `tokens_estimated`    TINYINT      NOT NULL DEFAULT 0 COMMENT '1=按 chars/2 估算',
    `duration_ms`         INT          NULL COMMENT '总耗时毫秒',
    `error_code`          VARCHAR(64)  NULL COMMENT '稳定错误码',
    `cancel_requested_at` DATETIME     NULL COMMENT 'M10 转向/取消使用，本期只建列',
    `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `finished_at`         DATETIME     NULL COMMENT '结束时间',
    PRIMARY KEY (`id`),
    KEY `idx_run_student` (`student_id`, `course_id`, `created_at`),
    KEY `idx_run_conversation` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 执行轨迹（run）【V10】';

CREATE TABLE `agent_step` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '轨迹 step ID',
    `run_id`      BIGINT      NOT NULL COMMENT '所属 agent_run.id',
    `seq`         INT         NOT NULL COMMENT 'run 内序号',
    `step_type`   VARCHAR(16) NOT NULL COMMENT 'LLM_CALL/TOOL_CALL/GUARD/DEGRADE/REFLECT/MEMORY/RETRIEVE',
    `name`        VARCHAR(64) NOT NULL COMMENT '工具名或 phase1/phase2/...',
    `status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '0=OK,1=FAILED,2=SKIPPED,3=CACHED',
    `input_json`  MEDIUMTEXT  NULL COMMENT '超 limits.trace.max-io-chars 截断并加 [truncated] 标记',
    `output_json` MEDIUMTEXT  NULL COMMENT '输出 JSON（可截断）',
    `tokens_in`   INT         NOT NULL DEFAULT 0 COMMENT '输入 token 数',
    `tokens_out`  INT         NOT NULL DEFAULT 0 COMMENT '输出 token 数',
    `duration_ms` INT         NULL COMMENT '步骤耗时毫秒',
    `error_code`  VARCHAR(64) NULL COMMENT '稳定错误码',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_step_seq` (`run_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 执行轨迹（step）【V10】';

-- ───────────────────────────── 组：评测场景与结果【V11】 ─────────────────────────────

DROP TABLE IF EXISTS `eval_case_result`;
DROP TABLE IF EXISTS `eval_scenario`;
CREATE TABLE `eval_scenario` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '场景 ID',
    `code`            VARCHAR(32)  NOT NULL COMMENT '场景编码（如 S01）',
    `title`           VARCHAR(100) NOT NULL COMMENT '场景标题',
    `persona_json`    TEXT         NOT NULL COMMENT '画像 JSON',
    `script_json`     TEXT         NOT NULL COMMENT '脚本轮次 JSON',
    `assertions_json` TEXT         NOT NULL COMMENT '硬断言 + judge 维度 JSON',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '0=DISABLED,1=ENABLED,2=PENDING',
    `pending_reason`  VARCHAR(64)  NULL COMMENT '如 M9-reflection',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scenario_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测场景【V11】';

CREATE TABLE `eval_case_result` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '结果 ID',
    `batch_id`     VARCHAR(64) NOT NULL COMMENT '批次 ID',
    `scenario_id`  BIGINT      NOT NULL COMMENT '关联 eval_scenario.id',
    `mode`         TINYINT     NOT NULL COMMENT '0=REPLAY,1=LIVE',
    `hard_pass`    TINYINT     NOT NULL COMMENT '硬断言是否通过',
    `judge_json`   TEXT        NULL COMMENT '各维度分与理由',
    `judge_model`  VARCHAR(64) NULL COMMENT 'judge 模型标识',
    `passed`       TINYINT     NOT NULL COMMENT '综合是否通过',
    `run_id`       BIGINT      NULL COMMENT '关联 agent_run.id（可空）',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_result_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测结果【V11】';

-- ───────────────────────────── 组：学习计划【V14】─────────────────────────────

DROP TABLE IF EXISTS `tool_effect_key`;
DROP TABLE IF EXISTS `agent_plan_task`;
DROP TABLE IF EXISTS `agent_plan`;

CREATE TABLE `agent_plan` (
    `id`             BIGINT   NOT NULL AUTO_INCREMENT,
    `student_id`     BIGINT   NOT NULL,
    `course_id`      BIGINT   NOT NULL,
    `goal_type`      TINYINT  NOT NULL DEFAULT 0 COMMENT '0=EXAM_PREP',
    `target_date`    DATE     NOT NULL,
    `status`         TINYINT  NOT NULL DEFAULT 0 COMMENT '0=DRAFT,1=ACTIVE,2=DONE,3=CANCELLED',
    `scope_json`     TEXT     NULL COMMENT '限定节点集，空=全图谱；可含 at_risk 等标志',
    `created_run_id` BIGINT   NULL,
    `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `confirmed_at`   DATETIME NULL,
    PRIMARY KEY (`id`),
    KEY `idx_plan_student` (`student_id`, `course_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习计划【V14】';

CREATE TABLE `agent_plan_task` (
    `id`        BIGINT      NOT NULL AUTO_INCREMENT,
    `plan_id`   BIGINT      NOT NULL,
    `task_date` DATE        NOT NULL,
    `node_code` VARCHAR(32) NOT NULL,
    `task_kind` TINYINT     NOT NULL COMMENT '0=REVIEW,1=LEARN,2=PRACTICE',
    `status`    TINYINT     NOT NULL DEFAULT 0 COMMENT '0=PENDING,1=DONE,2=SKIPPED,3=RESCHEDULED',
    `origin`    TINYINT     NOT NULL DEFAULT 0 COMMENT '0=INITIAL,1=REPLAN',
    PRIMARY KEY (`id`),
    KEY `idx_task_plan_date` (`plan_id`, `task_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计划每日任务【V14】';

CREATE TABLE `tool_effect_key` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `tool_name`   VARCHAR(64)  NOT NULL,
    `request_key` VARCHAR(128) NOT NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_effect` (`tool_name`, `request_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='写类工具幂等键（取消/重试防双写）【V14】';

-- ───────────────────────────── 组：多智能体报告【V15】─────────────────────────────

DROP TABLE IF EXISTS `agent_report`;

CREATE TABLE `agent_report` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `course_id`    BIGINT      NOT NULL,
    `kind`         TINYINT     NOT NULL COMMENT '0=TEACHER_WEEKLY,1=SUPERVISOR_SAMPLE',
    `period_start` DATE        NOT NULL,
    `period_end`   DATE        NOT NULL,
    `status`       TINYINT     NOT NULL DEFAULT 0 COMMENT '0=PENDING,1=READY,2=FAILED',
    `payload_json` MEDIUMTEXT  NULL COMMENT '报告结构化内容',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_report_period` (`course_id`, `kind`, `period_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多智能体产出【V15】';

-- ───────────────────────────── 组：审计与监控【V16】─────────────────────────────

DROP TABLE IF EXISTS `audit_log`;
CREATE TABLE `audit_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '审计日志ID',
    `operator_id`   BIGINT       NULL                    COMMENT '操作人 sys_user.id；匿名为 NULL',
    `operator_name` VARCHAR(64)  NULL                    COMMENT '冗余用户名，防用户删改后无从追溯',
    `operator_role` TINYINT      NULL                    COMMENT '操作人角色：1=教师, 2=学生, 3=管理员',
    `action`        VARCHAR(64)  NOT NULL                COMMENT '动作编码，如 USER_CREATE / COURSE_DELETE',
    `target_type`   VARCHAR(32)  NULL                    COMMENT '对象类型：USER/COURSE/GRAPH/QUESTION/RAG',
    `target_id`     VARCHAR(64)  NULL                    COMMENT '对象ID，取自路径变量 id/courseId/draftId',
    `http_method`   VARCHAR(8)   NOT NULL                COMMENT 'HTTP 方法',
    `path`          VARCHAR(255) NOT NULL                COMMENT '请求路径（不含查询串）',
    `http_status`   INT          NOT NULL                COMMENT 'HTTP 响应状态码',
    `success`       TINYINT      NOT NULL                COMMENT '1=状态码<400 且无异常, 0=失败',
    `ip`            VARCHAR(64)  NULL                    COMMENT '客户端 IP（优先 X-Forwarded-For 首段）',
    `request_id`    VARCHAR(64)  NULL                    COMMENT '请求ID（关联 RequestIdFilter）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (`id`),
    KEY `idx_audit_time` (`created_at`),
    KEY `idx_audit_operator` (`operator_id`, `created_at`),
    KEY `idx_audit_action` (`action`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关键操作审计日志【V16】';

DROP TABLE IF EXISTS `login_log`;
CREATE TABLE `login_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '登录日志ID',
    `user_id`     BIGINT       NULL                    COMMENT '用户ID；用户名不存在时为 NULL',
    `username`    VARCHAR(64)  NOT NULL                COMMENT '尝试登录的用户名（截断到64）',
    `success`     TINYINT      NOT NULL                COMMENT '1=成功, 0=失败',
    `fail_reason` VARCHAR(64)  NULL                    COMMENT '失败原因：BAD_CREDENTIALS / DISABLED',
    `ip`          VARCHAR(64)  NULL                    COMMENT '客户端 IP（优先 X-Forwarded-For 首段）',
    `user_agent`  VARCHAR(255) NULL                    COMMENT '浏览器 User-Agent（截断到255）',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
    PRIMARY KEY (`id`),
    KEY `idx_login_time` (`created_at`),
    KEY `idx_login_user` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志【V16】';

-- ───────────────────────────── 组：班级【V17】─────────────────────────────
--  班级是"批量选课"的手段，不替代学生自选；移除学生 / 解除课程 / 删除班级都不动 student_course。

DROP TABLE IF EXISTS `clazz`;
CREATE TABLE `clazz` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '班级ID',
    `name`        VARCHAR(64)  NOT NULL                COMMENT '班级名，如 软件工程2201',
    `description` VARCHAR(255) NULL                    COMMENT '班级说明',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_clazz_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级【V17】';

DROP TABLE IF EXISTS `clazz_student`;
CREATE TABLE `clazz_student` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `clazz_id`   BIGINT   NOT NULL                COMMENT '班级（物理外键→clazz.id，级联删除）',
    `student_id` BIGINT   NOT NULL                COMMENT '学生（物理外键→sys_user.id；唯一：一个学生至多一个班级）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_clazz_student_student` (`student_id`),
    KEY `idx_clazz_student_clazz` (`clazz_id`),
    CONSTRAINT `fk_cs_clazz` FOREIGN KEY (`clazz_id`) REFERENCES `clazz` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_cs_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级成员（一个学生至多一个班级）【V17】';

DROP TABLE IF EXISTS `clazz_course`;
CREATE TABLE `clazz_course` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `clazz_id`   BIGINT   NOT NULL                COMMENT '班级（物理外键→clazz.id，级联删除）',
    `course_id`  BIGINT   NOT NULL                COMMENT '课程（物理外键→course.id）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关联时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_clazz_course` (`clazz_id`, `course_id`),
    KEY `idx_clazz_course_course` (`course_id`),
    CONSTRAINT `fk_cc_clazz` FOREIGN KEY (`clazz_id`) REFERENCES `clazz` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_cc_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级关联课程【V17】';

-- ───────────────────────────── 组：系统配置【V18】─────────────────────────────
--  yml / 环境变量为默认值，本表覆盖白名单 key；启动与保存后写回 properties bean（热生效）。

DROP TABLE IF EXISTS `sys_config`;
CREATE TABLE `sys_config` (
    `config_key`   VARCHAR(128) NOT NULL                COMMENT '配置项 key，如 wenjin.ai.model（白名单登记）',
    `config_value` TEXT         NOT NULL                COMMENT '覆盖值；secret 类型为 AES-GCM 密文(base64)',
    `encrypted`    TINYINT      NOT NULL DEFAULT 0      COMMENT '1=config_value 为密文, 0=明文',
    `updated_by`   BIGINT       NULL                    COMMENT '最后修改人 sys_user.id（仅留痕，无物理外键）',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    PRIMARY KEY (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置覆盖层（白名单 key）【V18】';
-- ───────────────────────────── 组：今日复习置顶【V19】─────────────────────────────

DROP TABLE IF EXISTS `review_pin`;

CREATE TABLE `review_pin` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `student_id`   BIGINT       NOT NULL COMMENT '学生 sys_user.id',
    `course_id`    BIGINT       NOT NULL COMMENT '课程 course.id',
    `pin_date`     DATE         NOT NULL COMMENT '置顶所属自然日（Asia/Shanghai）',
    `node_code`    VARCHAR(32)  NOT NULL COMMENT '知识点 node_code',
    `mode`         VARCHAR(16)  NOT NULL COMMENT 'TODAY / ADVANCE',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_review_pin` (`student_id`, `course_id`, `pin_date`, `node_code`),
    KEY `idx_review_pin_lookup` (`student_id`, `course_id`, `pin_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='今日复习手动置顶（伴侣 schedule_review）【V19】';

-- ───────────────────────────── 组：主动陪伴提示日志【V22】─────────────────────────────

DROP TABLE IF EXISTS `companion_hint_log`;

CREATE TABLE `companion_hint_log` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `student_id`   BIGINT       NOT NULL COMMENT '学生 sys_user.id',
    `course_id`    BIGINT       NOT NULL COMMENT '课程 course.id',
    `hint_key`     VARCHAR(96)  NOT NULL COMMENT '提示实例键，如 ERROR_BURST:session:123',
    `hint_type`    VARCHAR(32)  NOT NULL COMMENT 'ERROR_BURST / DECAY / PLAN_BEHIND',
    `shown_at`     DATETIME     NOT NULL COMMENT '最近一次展示时间',
    `dismissed_at` DATETIME     NULL COMMENT '学生关闭时间；非空则不再展示',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_companion_hint` (`student_id`, `course_id`, `hint_key`),
    KEY `idx_companion_hint_shown` (`student_id`, `course_id`, `shown_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='伴侣主动提示条展示/关闭日志【V22】';

-- ───────────────────────────── 组：RAG 切块向量【V23】─────────────────────────────

DROP TABLE IF EXISTS `doc_chunk_embedding`;

CREATE TABLE `doc_chunk_embedding` (
    `chunk_id`    BIGINT      NOT NULL COMMENT '切块（逻辑外键→doc_chunk.id）',
    `model`       VARCHAR(64) NOT NULL COMMENT '嵌入模型标识，如 embedding-3@1024',
    `dim`         INT         NOT NULL COMMENT '向量维度',
    `vector`      MEDIUMBLOB  NOT NULL COMMENT 'float32 little-endian',
    `content_sha` CHAR(64)    NOT NULL COMMENT '切块内容 SHA-256，内容未变则跳过重嵌',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '嵌入时间',
    PRIMARY KEY (`chunk_id`),
    KEY `idx_chunk_emb_model` (`model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='语料切块向量【V23】';

-- ───────────────────────────── 组：编程题测试用例【V24】─────────────────────────────

DROP TABLE IF EXISTS `question_code_case`;

CREATE TABLE `question_code_case` (
    `id`              BIGINT     NOT NULL AUTO_INCREMENT,
    `question_id`     BIGINT     NOT NULL COMMENT '所属编程题（逻辑外键→question.id）',
    `seq`             INT        NOT NULL COMMENT '用例序号（从 1 起，题内唯一）',
    `stdin`           TEXT       NULL COMMENT '标准输入',
    `expected_stdout` TEXT       NULL COMMENT '期望标准输出',
    `hidden`          TINYINT    NOT NULL DEFAULT 0 COMMENT '1=隐藏用例（学生侧不可见输入/期望/实际）',
    `created_at`      DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_case_question_seq` (`question_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编程题测试用例【V24】';

SET FOREIGN_KEY_CHECKS = 1;
