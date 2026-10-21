-- V16: 管理后台 A2 审计与监控 — audit_log / login_log，以及 sys_user 令牌吊销水位线
--
-- 两张日志表都不加外键：日志要能比用户活得久（用户被删改后仍可追溯）。
-- 暂不做保留/清理策略；列表查询必须分页且走 created_at 索引。

CREATE TABLE `audit_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `operator_id`   BIGINT       NULL COMMENT '操作人 sys_user.id；匿名为 NULL',
    `operator_name` VARCHAR(64)  NULL COMMENT '冗余用户名，防用户删改后无从追溯',
    `operator_role` TINYINT      NULL COMMENT '操作人角色：1=教师, 2=学生, 3=管理员',
    `action`        VARCHAR(64)  NOT NULL COMMENT '如 USER_CREATE / COURSE_DELETE',
    `target_type`   VARCHAR(32)  NULL COMMENT 'USER/COURSE/GRAPH/QUESTION/RAG',
    `target_id`     VARCHAR(64)  NULL COMMENT '取自路径变量 id/courseId/draftId',
    `http_method`   VARCHAR(8)   NOT NULL,
    `path`          VARCHAR(255) NOT NULL,
    `http_status`   INT          NOT NULL,
    `success`       TINYINT      NOT NULL COMMENT '1=状态码<400 且无异常',
    `ip`            VARCHAR(64)  NULL,
    `request_id`    VARCHAR(64)  NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_audit_time` (`created_at`),
    KEY `idx_audit_operator` (`operator_id`, `created_at`),
    KEY `idx_audit_action` (`action`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关键操作审计日志';

CREATE TABLE `login_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT       NULL COMMENT '用户名不存在时为 NULL',
    `username`    VARCHAR(64)  NOT NULL COMMENT '尝试登录的用户名（截断到64）',
    `success`     TINYINT      NOT NULL,
    `fail_reason` VARCHAR(64)  NULL COMMENT 'BAD_CREDENTIALS / DISABLED',
    `ip`          VARCHAR(64)  NULL,
    `user_agent`  VARCHAR(255) NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_login_time` (`created_at`),
    KEY `idx_login_user` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志';

-- 令牌吊销水位线：管理员重置密码时写入，早于此刻签发的令牌一律无效。
-- NULL = 从未吊销过（含升级前签发、不带 iat 的旧令牌，照常可用）。
ALTER TABLE `sys_user`
    ADD COLUMN `token_valid_after` DATETIME(3) NULL COMMENT '早于此刻签发的令牌一律无效（重置密码时写入）';
