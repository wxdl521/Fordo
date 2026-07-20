-- V14: M10 planning core — agent_plan / agent_plan_task / tool_effect_key
-- Constraint (service layer): at most 1 DRAFT + 1 ACTIVE per (student_id, course_id).

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习计划';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计划每日任务';

CREATE TABLE `tool_effect_key` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `tool_name`   VARCHAR(64)  NOT NULL,
    `request_key` VARCHAR(128) NOT NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_effect` (`tool_name`, `request_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='写类工具幂等键（取消/重试防双写）';
