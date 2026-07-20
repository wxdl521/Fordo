-- V12: student long-term memory + companion distill / summary-origin columns (M7)

CREATE TABLE `student_memory` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生长期记忆（M7）';

ALTER TABLE `companion_conversation`
    ADD COLUMN `distilled_through_message_id` BIGINT NULL
        COMMENT '已蒸馏到的最后一条消息 id（含）' AFTER `summary_through_message_id`,
    ADD COLUMN `summary_origin` TINYINT NOT NULL DEFAULT 0
        COMMENT '0=DETERMINISTIC mergeSummary,1=LLM SUMMARY_REFRESH' AFTER `summary_text`;
