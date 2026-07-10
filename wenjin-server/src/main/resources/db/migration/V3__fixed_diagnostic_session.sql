-- V3: freeze fixed diagnostic papers; exact-set submit; idempotent result_json
-- One-shot Flyway migration (no DELIMITER / stored procedures).
-- Pre-check: ADD UNIQUE fails if duplicates already exist (acts as SIGNAL).

-- ── 1. Composite unique on question(id, course_id) for composite FK targets ──
-- id is PK so rows are already unique; key enables (question_id, course_id) FKs.
ALTER TABLE `question`
    ADD UNIQUE KEY `uk_question_id_course` (`id`, `course_id`);

-- ── 2. diagnostic_session ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `diagnostic_session` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '固定诊断会话ID',
    `token`       CHAR(36)     NOT NULL                COMMENT '会话令牌 UUID（客户端持有）',
    `student_id`  BIGINT       NOT NULL                COMMENT '学生（逻辑外键→sys_user.id）',
    `course_id`   BIGINT       NOT NULL                COMMENT '课程（逻辑外键→course.id）',
    `status`      TINYINT      NOT NULL DEFAULT 0      COMMENT '状态：0=进行中, 1=已完成',
    `result_json` MEDIUMTEXT   DEFAULT NULL            COMMENT '完成后幂等重放用的判分结果 JSON',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `finished_at` DATETIME     DEFAULT NULL            COMMENT '完成时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token` (`token`),
    KEY `idx_student_course` (`student_id`, `course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='固定卷诊断会话（组卷即冻结题集）';

-- ── 3. diagnostic_session_question ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `diagnostic_session_question` (
    `id`          BIGINT  NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`  BIGINT  NOT NULL                COMMENT '会话（逻辑外键→diagnostic_session.id）',
    `question_id` BIGINT  NOT NULL                COMMENT '题目（逻辑外键→question.id）',
    `course_id`   BIGINT  NOT NULL                COMMENT '课程（与 question.course_id 一致）',
    `ordinal`     INT     NOT NULL                COMMENT '卷内顺序（0-based）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_question` (`session_id`, `question_id`),
    KEY `idx_session_ordinal` (`session_id`, `ordinal`),
    CONSTRAINT `fk_dsq_session`
        FOREIGN KEY (`session_id`) REFERENCES `diagnostic_session` (`id`),
    CONSTRAINT `fk_dsq_question_course`
        FOREIGN KEY (`question_id`, `course_id`) REFERENCES `question` (`id`, `course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='固定卷诊断会话冻结题目';

-- ── 4. answer_record.fixed_diagnostic_session_id + unique (session, question) ─
ALTER TABLE `answer_record`
    ADD COLUMN `fixed_diagnostic_session_id` BIGINT DEFAULT NULL
        COMMENT '固定诊断会话ID（scene=1 固定卷时非空，逻辑外键→diagnostic_session.id）';

-- Pre-check via unique: fails migration if duplicate non-null pairs exist
ALTER TABLE `answer_record`
    ADD UNIQUE KEY `uk_fixed_diag_session_question` (`fixed_diagnostic_session_id`, `question_id`);
