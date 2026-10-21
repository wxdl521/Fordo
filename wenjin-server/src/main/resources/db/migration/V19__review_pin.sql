-- V19: companion schedule_review durable pins for today's review queue
CREATE TABLE IF NOT EXISTS `review_pin` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `student_id`   BIGINT       NOT NULL COMMENT '学生 sys_user.id',
    `course_id`    BIGINT       NOT NULL COMMENT '课程 course.id',
    `pin_date`     DATE         NOT NULL COMMENT '置顶所属自然日（Asia/Shanghai）',
    `node_code`    VARCHAR(32)  NOT NULL COMMENT '知识点 node_code',
    `mode`         VARCHAR(16)  NOT NULL COMMENT 'TODAY / ADVANCE',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_review_pin` (`student_id`, `course_id`, `pin_date`, `node_code`),
    KEY `idx_review_pin_lookup` (`student_id`, `course_id`, `pin_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='今日复习手动置顶（伴侣 schedule_review）';
