-- ════════════════════════════════════════════════════════════════════════
--  问津 · 知识图谱驱动的个性化学习导航系统
--  本地空库重建快照（当前最终 DDL，含 V3–V9；共 23 张业务表）
--
--  ⚠ 仅本地空库 / 会删数据 / 生产禁用
--    · 本脚本含 DROP TABLE IF EXISTS，会清空目标库业务表数据。
--    · 生产与 CI 一律走 Flyway：classpath:db/migration（V1 baseline + 增量至 V9）。
--    · 新增版本化迁移（Vn）后，必须把最终 DDL 同步回本文件，保持本地快照一致。
--    · 应用启动默认 spring.flyway.enabled=false（WENJIN_FLYWAY_ENABLED），
--      普通应用账号只有 DML；发布流水线用 migrator 凭据单独跑迁移作业。
--
--  使用说明（仅本地）：
--    1. 本脚本可重复执行（FOREIGN_KEY_CHECKS=0 下 DROP IF EXISTS 再 CREATE）。
--    2. 字符集统一 utf8mb4，存储引擎 InnoDB。
--    3. 所有表、字段均带中文 COMMENT。
--    4. V9 起建立命名物理外键与 sys_user.role CHECK；本地快照与 Flyway 最终态对齐。
--    5. 演示种子数据见 data.sql（本地：schema.sql + data.sql；生产勿跑 data.sql）。
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
    `role`       TINYINT      NOT NULL                COMMENT '角色：1=教师, 2=学生, 3=管理员',
    `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1=正常, 0=禁用',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                          COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
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
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_course_code` (`course_id`, `node_code`) COMMENT '同一课程内业务ID唯一（对应导入校验：重复ID检测）',
    KEY `idx_course` (`course_id`),
    KEY `idx_node_course_active` (`course_id`, `archived_at`, `id`),
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
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_edge` (`course_id`, `from_node_id`, `to_node_id`, `relation_type`) COMMENT '防重复边',
    KEY `idx_from` (`from_node_id`),
    KEY `idx_to` (`to_node_id`),
    KEY `idx_edge_course_active` (`course_id`, `archived_at`, `id`),
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
    `session_id`     BIGINT   DEFAULT NULL            COMMENT '练习会话ID（scene=2 时非空，物理外键→practice_session.id）【M1补充】',
    `fixed_diagnostic_session_id` BIGINT DEFAULT NULL COMMENT '固定诊断会话ID（scene=1 固定卷时非空，物理外键→diagnostic_session.id）【V3】',
    PRIMARY KEY (`id`),
    KEY `idx_student_course` (`student_id`, `course_id`),
    KEY `idx_question` (`question_id`),
    UNIQUE KEY `uk_fixed_diag_session_question` (`fixed_diagnostic_session_id`, `question_id`),
    CONSTRAINT `fk_answer_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    CONSTRAINT `fk_answer_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
    CONSTRAINT `fk_answer_question` FOREIGN KEY (`question_id`) REFERENCES `question` (`id`),
    CONSTRAINT `fk_answer_practice_session` FOREIGN KEY (`session_id`) REFERENCES `practice_session` (`id`),
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
    `summary_through_message_id` BIGINT NULL          COMMENT '摘要覆盖到的最后一条消息 id（含）【V8】',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                          COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_student_course` (`student_id`, `course_id`),
    CONSTRAINT `fk_companion_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    CONSTRAINT `fk_companion_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 学习伴侣会话';

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
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `started_at`   DATETIME     NULL                    COMMENT '最近一次开始执行时间',
    `finished_at`  DATETIME     NULL                    COMMENT '结束时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_job_request` (`owner_id`, `kind`, `request_key`),
    KEY `idx_ai_job_claim` (`status`, `created_at`),
    CONSTRAINT `fk_ai_job_owner` FOREIGN KEY (`owner_id`) REFERENCES `sys_user` (`id`),
    CONSTRAINT `fk_ai_job_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 异步写作业（可恢复）【V7】';

SET FOREIGN_KEY_CHECKS = 1;
