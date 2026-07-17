-- V7: recoverable AI write jobs (generate / annotate / import / extract)

CREATE TABLE `ai_job` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `kind`         VARCHAR(32)  NOT NULL,
    `owner_id`     BIGINT       NOT NULL,
    `course_id`    BIGINT       NOT NULL,
    `request_key`  VARCHAR(64)  NOT NULL,
    `status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING,1=RUNNING,2=SUCCEEDED,3=FAILED,4=CANCELLED',
    `progress`     INT          NOT NULL DEFAULT 0,
    `attempts`     INT          NOT NULL DEFAULT 0,
    `request_json` MEDIUMTEXT   NOT NULL,
    `result_json`  MEDIUMTEXT   NULL,
    `error_code`   VARCHAR(64)  NULL,
    `cancel_requested_at` DATETIME NULL,
    `lease_token`  CHAR(36)     NULL,
    `lease_until`  DATETIME     NULL,
    `heartbeat_at` DATETIME     NULL,
    `effect_started_at` DATETIME NULL,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `started_at`   DATETIME     NULL,
    `finished_at`  DATETIME     NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_job_request` (`owner_id`, `kind`, `request_key`),
    KEY `idx_ai_job_claim` (`status`, `created_at`),
    KEY `idx_ai_job_lease` (`status`, `lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 异步写作业（可恢复）';
