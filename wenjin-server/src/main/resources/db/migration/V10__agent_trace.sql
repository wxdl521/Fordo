-- V10: Agent execution trace (run + step) for companion/diagnostic/plan observability

CREATE TABLE `agent_run` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `request_id`          VARCHAR(64)  NULL COMMENT '关联 RequestIdFilter',
    `student_id`          BIGINT       NOT NULL,
    `course_id`           BIGINT       NOT NULL,
    `conversation_id`     BIGINT       NULL,
    `kind`                VARCHAR(32)  NOT NULL COMMENT 'COMPANION_CHAT/DIAGNOSTIC/PLAN_GENERATE/PLAN_REPLAN/...',
    `status`              TINYINT      NOT NULL DEFAULT 0 COMMENT '0=RUNNING,1=SUCCEEDED,2=FAILED,3=DEGRADED,4=CANCELLED',
    `model`               VARCHAR(64)  NULL,
    `input_digest`        VARCHAR(200) NULL COMMENT '首条输入前 200 字，非全文',
    `tokens_in`           INT          NOT NULL DEFAULT 0,
    `tokens_out`          INT          NOT NULL DEFAULT 0,
    `tokens_estimated`    TINYINT      NOT NULL DEFAULT 0 COMMENT '1=按 chars/2 估算',
    `duration_ms`         INT          NULL,
    `error_code`          VARCHAR(64)  NULL,
    `cancel_requested_at` DATETIME     NULL COMMENT 'M10 转向/取消使用，本期只建列',
    `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finished_at`         DATETIME     NULL,
    PRIMARY KEY (`id`),
    KEY `idx_run_student` (`student_id`, `course_id`, `created_at`),
    KEY `idx_run_conversation` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 执行轨迹（run）';

CREATE TABLE `agent_step` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `run_id`      BIGINT      NOT NULL,
    `seq`         INT         NOT NULL,
    `step_type`   VARCHAR(16) NOT NULL COMMENT 'LLM_CALL/TOOL_CALL/GUARD/DEGRADE/REFLECT/MEMORY/RETRIEVE',
    `name`        VARCHAR(64) NOT NULL COMMENT '工具名或 phase1/phase2/...',
    `status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '0=OK,1=FAILED,2=SKIPPED,3=CACHED',
    `input_json`  MEDIUMTEXT  NULL COMMENT '超 limits.trace.max-io-chars 截断并加 [truncated] 标记',
    `output_json` MEDIUMTEXT  NULL,
    `tokens_in`   INT         NOT NULL DEFAULT 0,
    `tokens_out`  INT         NOT NULL DEFAULT 0,
    `duration_ms` INT         NULL,
    `error_code`  VARCHAR(64) NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_step_seq` (`run_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 执行轨迹（step）';
