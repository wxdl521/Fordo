-- V22: 主动陪伴提示条展示/关闭日志（方向 4）
CREATE TABLE IF NOT EXISTS `companion_hint_log` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `student_id`   BIGINT       NOT NULL COMMENT '学生 sys_user.id',
    `course_id`    BIGINT       NOT NULL COMMENT '课程 course.id',
    `hint_key`     VARCHAR(96)  NOT NULL COMMENT '提示实例键，如 ERROR_BURST:session:123',
    `hint_type`    VARCHAR(32)  NOT NULL COMMENT 'ERROR_BURST / DECAY / PLAN_BEHIND',
    `shown_at`     DATETIME     NOT NULL COMMENT '最近一次展示时间',
    `dismissed_at` DATETIME     NULL COMMENT '学生关闭时间；非空则不再展示',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_companion_hint` (`student_id`, `course_id`, `hint_key`),
    KEY `idx_companion_hint_shown` (`student_id`, `course_id`, `shown_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='伴侣主动提示条展示/关闭日志';
