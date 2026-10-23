-- V26: 理解探针（M9）状态落库——会话 + 每节点最近一次结果。此前为进程内 Map，重启即丢。
-- 无物理 FK（与 V22+ 一致）；探针刻意不写 student_mastery / answer_record（EWMA 隔离），这两张表只存探针自身状态。
-- 两张表而非一张：会话是「每次探针一行、需保留历史以免重复出题」，结果是「每 学生×课程×节点 仅最近一行 + 换策略状态机」，键与生命周期不同。
CREATE TABLE IF NOT EXISTS `probe_session` (
    `session_id`       VARCHAR(40)  NOT NULL COMMENT '探针会话ID（UUID 去横线，前端沿用的字符串）',
    `student_id`       BIGINT       NOT NULL COMMENT '学生 sys_user.id',
    `course_id`        BIGINT       NOT NULL COMMENT '课程 course.id',
    `node_code`        VARCHAR(64)  NOT NULL COMMENT '被探测知识点 kg_node.node_code',
    `node_id`          BIGINT       NULL COMMENT 'kg_node.id（逻辑引用）',
    `stem`             TEXT         NOT NULL COMMENT '题干',
    `type`             TINYINT      NOT NULL DEFAULT 1 COMMENT '题型（同 question.type）',
    `options_json`     TEXT         NULL COMMENT '选项 JSON [{key,text}]（不含答案）',
    `correct_key`      VARCHAR(255) NULL COMMENT '标准答案键；仅服务端判分用，不下发客户端',
    `source`           VARCHAR(16)  NOT NULL COMMENT 'BANK=取自题库 / EPHEMERAL=临时模板题',
    `bank_question_id` BIGINT       NULL COMMENT '题库题 question.id（仅 BANK）',
    `created_at`       DATETIME(3)  NOT NULL COMMENT '创建时间（用于 TTL）',
    `submitted_at`     DATETIME(3)  NULL COMMENT '首次提交时间；NULL=未提交',
    `correct`          TINYINT(1)   NULL COMMENT '首次判分结果；未提交为 NULL',
    PRIMARY KEY (`session_id`),
    KEY `idx_probe_session_student` (`student_id`, `course_id`, `created_at`),
    KEY `idx_probe_session_node` (`student_id`, `course_id`, `node_code`),
    KEY `idx_probe_session_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='理解探针会话';

CREATE TABLE IF NOT EXISTS `probe_outcome` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `student_id`       BIGINT       NOT NULL COMMENT '学生 sys_user.id',
    `course_id`        BIGINT       NOT NULL COMMENT '课程 course.id',
    `node_code`        VARCHAR(64)  NOT NULL COMMENT '知识点 kg_node.node_code',
    `correct`          TINYINT(1)   NOT NULL COMMENT '最近一次探针是否通过',
    `strategy`         VARCHAR(32)  NULL COMMENT '该探针所检验的讲解策略（NULL=默认讲法）',
    `active_strategy`  VARCHAR(32)  NULL COMMENT '当前生效（最近换上）的策略',
    `tried_strategies` VARCHAR(255) NULL COMMENT '本轮反思已用策略，逗号分隔（通过后清空）',
    `switch_consumed`  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '这次未通过已触发过换策略',
    `outcome_recorded` TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'strategy 的有效/无效已写入程序性记忆',
    `updated_at`       DATETIME(3)  NOT NULL COMMENT '探针提交时间（30 分钟窗口基准；换策略/记录标记不刷新）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_probe_outcome_node` (`student_id`, `course_id`, `node_code`),
    KEY `idx_probe_outcome_recent` (`student_id`, `course_id`, `updated_at`),
    KEY `idx_probe_outcome_updated` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='理解探针最近一次结果与换策略状态';
