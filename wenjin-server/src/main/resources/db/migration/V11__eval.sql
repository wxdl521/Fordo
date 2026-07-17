-- V11: Eval scenario catalog + case results (M6)

CREATE TABLE `eval_scenario` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `code`            VARCHAR(32)  NOT NULL,
    `title`           VARCHAR(100) NOT NULL,
    `persona_json`    TEXT         NOT NULL,
    `script_json`     TEXT         NOT NULL,
    `assertions_json` TEXT         NOT NULL,
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '0=DISABLED,1=ENABLED,2=PENDING',
    `pending_reason`  VARCHAR(64)  NULL COMMENT '如 M9-reflection',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_scenario_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测场景';

CREATE TABLE `eval_case_result` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `batch_id`     VARCHAR(64) NOT NULL,
    `scenario_id`  BIGINT      NOT NULL,
    `mode`         TINYINT     NOT NULL COMMENT '0=REPLAY,1=LIVE',
    `hard_pass`    TINYINT     NOT NULL,
    `judge_json`   TEXT        NULL COMMENT '各维度分与理由',
    `judge_model`  VARCHAR(64) NULL,
    `passed`       TINYINT     NOT NULL,
    `run_id`       BIGINT      NULL,
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), KEY `idx_result_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测结果';
