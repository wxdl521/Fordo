-- ════════════════════════════════════════════════════════════════════════
--  M1 练习闭环 · 增量升级脚本
--  适用：已有库升级（新建库直接跑 schema.sql 即可）
--  幂等：可连续执行两次，第二次无副作用
--
--  变更内容：
--    1. 新建 practice_session 表（节点练习会话）
--    2. answer_record 表新增 scene / session_id 两列
-- ════════════════════════════════════════════════════════════════════════

USE `wenjin`;

-- ── 1. 新建 practice_session 表（IF NOT EXISTS 本身幂等） ──────────────────

CREATE TABLE IF NOT EXISTS `practice_session` (
    `id`           BIGINT   NOT NULL AUTO_INCREMENT COMMENT '练习会话ID',
    `student_id`   BIGINT   NOT NULL COMMENT '学生（逻辑外键→sys_user.id）',
    `course_id`    BIGINT   NOT NULL COMMENT '课程（逻辑外键→course.id）',
    `node_id`      BIGINT   NOT NULL COMMENT '目标知识点（逻辑外键→kg_node.id）',
    `path_item_id` BIGINT   DEFAULT NULL COMMENT '来源路径步骤（可空=自由练习，逻辑外键→learning_path_item.id）',
    `question_ids` VARCHAR(512) NOT NULL COMMENT '本会话题目ID列表，逗号分隔（组卷即冻结，防提交时偷换题目）',
    `status`       TINYINT  NOT NULL DEFAULT 0 COMMENT '状态：0=进行中, 1=已提交',
    `created_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `submitted_at` DATETIME DEFAULT NULL COMMENT '提交时间',
    PRIMARY KEY (`id`),
    KEY `idx_student_course_node` (`student_id`, `course_id`, `node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节点练习会话（M1 练习闭环）';

-- ── 2. answer_record 加两列（MySQL 不支持 ADD COLUMN IF NOT EXISTS，用存储过程预查） ──

DROP PROCEDURE IF EXISTS `wj_m01_add_answer_record_columns`;

DELIMITER //
CREATE PROCEDURE `wj_m01_add_answer_record_columns`()
BEGIN
    -- 2a. scene 列
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'answer_record'
          AND COLUMN_NAME  = 'scene'
    ) THEN
        ALTER TABLE `answer_record`
            ADD COLUMN `scene` TINYINT NOT NULL DEFAULT 1
                COMMENT '作答场景：1=诊断, 2=节点练习';
    END IF;

    -- 2b. session_id 列
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'answer_record'
          AND COLUMN_NAME  = 'session_id'
    ) THEN
        ALTER TABLE `answer_record`
            ADD COLUMN `session_id` BIGINT DEFAULT NULL
                COMMENT '练习会话ID（scene=2 时非空）';
    END IF;
END //
DELIMITER ;

CALL `wj_m01_add_answer_record_columns`();
DROP PROCEDURE IF EXISTS `wj_m01_add_answer_record_columns`;
