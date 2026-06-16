USE `wenjin`;

-- ───────────────────────────── 组X：AI 学习伴侣（阶段五新增） ─────────────────────────────

DROP TABLE IF EXISTS `companion_conversation`;
CREATE TABLE `companion_conversation` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    `student_id` BIGINT       NOT NULL                COMMENT '学生（逻辑外键→sys_user.id）',
    `course_id`  BIGINT       NOT NULL                COMMENT '课程（逻辑外键→course.id）',
    `title`      VARCHAR(128) DEFAULT NULL            COMMENT '会话标题（首问截断/节点名前缀）',
    `node_code`  VARCHAR(64)  DEFAULT NULL            COMMENT '发起时的图谱节点上下文（可空）',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                          COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_student_course` (`student_id`, `course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 学习伴侣会话';

DROP TABLE IF EXISTS `companion_message`;
CREATE TABLE `companion_message` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `conversation_id` BIGINT   NOT NULL                COMMENT '所属会话（逻辑外键→companion_conversation.id）',
    `role`            TINYINT  NOT NULL                COMMENT '角色：1=user, 2=ai',
    `content`         TEXT                             COMMENT '消息正文',
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_conversation` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='伴侣会话消息';
