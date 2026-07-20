-- V15: M11 multi-agent optional — agent_report (teacher weekly / supervisor sample)

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多智能体产出';
