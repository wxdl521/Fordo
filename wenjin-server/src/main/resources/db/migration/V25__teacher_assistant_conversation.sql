-- V25: 教师 AI 助教会话（方向 7）。独立于 companion_conversation/message（后者以 student_id 为键并触发记忆蒸馏）。
-- 无物理 FK（与 V22+ 一致）；归属 = teacher_id（ADMIN 也只能看自己的会话）。
CREATE TABLE IF NOT EXISTS `teacher_conversation` (
    `id`                         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    `teacher_id`                 BIGINT       NOT NULL COMMENT '会话所有者 sys_user.id（教师或管理员）',
    `course_id`                  BIGINT       NOT NULL COMMENT '课程 course.id（创建时已校验可管理）',
    `title`                      VARCHAR(128) NULL COMMENT '会话标题（首问截断）',
    `summary_text`               MEDIUMTEXT   NULL COMMENT '历史消息滚动摘要（送入模型）',
    `summary_through_message_id` BIGINT       NULL COMMENT '摘要覆盖到的最后一条消息 id（含）',
    `created_at`                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_teacher_conv_owner` (`teacher_id`, `course_id`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师 AI 助教会话';

CREATE TABLE IF NOT EXISTS `teacher_message` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `conversation_id` BIGINT   NOT NULL COMMENT '所属会话（逻辑外键→teacher_conversation.id）',
    `role`            TINYINT  NOT NULL COMMENT '角色：1=user, 2=ai',
    `content`         TEXT     NOT NULL COMMENT '消息正文',
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_teacher_msg_conv` (`conversation_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师 AI 助教会话消息';
