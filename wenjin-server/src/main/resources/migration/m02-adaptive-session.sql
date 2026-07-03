-- ════════════════════════════════════════════════════════════════════════
--  M2 图引导自适应诊断 · 增量升级脚本
--  适用：已有库升级（新建库直接跑 schema.sql 即可）
--  幂等：可连续执行两次，第二次无副作用（IF NOT EXISTS 保证）
--
--  变更内容：
--    1. 新建 adaptive_diag_session 表（自适应诊断会话）
-- ════════════════════════════════════════════════════════════════════════

USE `wenjin`;

-- ── 1. 新建 adaptive_diag_session 表（IF NOT EXISTS 本身幂等） ────────────

CREATE TABLE IF NOT EXISTS `adaptive_diag_session` (
    `id`          BIGINT     NOT NULL AUTO_INCREMENT COMMENT '自适应诊断会话ID',
    `student_id`  BIGINT     NOT NULL COMMENT '学生（逻辑外键→sys_user.id）',
    `course_id`   BIGINT     NOT NULL COMMENT '课程（逻辑外键→course.id）',
    `state_json`  MEDIUMTEXT NOT NULL COMMENT '会话状态快照：节点状态机/已用预算/当前探针（服务端为唯一事实源）',
    `status`      TINYINT    NOT NULL DEFAULT 0 COMMENT '状态：0=进行中, 1=已完成, 2=已放弃',
    `created_at`  DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `finished_at` DATETIME   DEFAULT NULL COMMENT '完成/放弃时间',
    PRIMARY KEY (`id`),
    KEY `idx_student_course` (`student_id`, `course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自适应诊断会话（M2 图引导自适应诊断）';
