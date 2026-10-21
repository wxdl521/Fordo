-- V17: 管理后台 A3 课程与班级 — clazz / clazz_student / clazz_course
--
-- 班级只是"批量选课"的手段，不替代学生自选：
--   · 课程关联到班级、学生加入班级时，由应用层用 INSERT IGNORE ... SELECT 给学生补 student_course；
--   · 移除学生 / 解除班级课程 / 删除班级都不碰 student_course（保留学习数据）。
-- 一个学生至多属于一个班级（uk_clazz_student_student）；删除班级级联删除其成员与课程关联行。

CREATE TABLE `clazz` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '班级ID',
    `name`        VARCHAR(64)  NOT NULL                COMMENT '班级名，如 软件工程2201',
    `description` VARCHAR(255) NULL                    COMMENT '班级说明',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_clazz_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级';

CREATE TABLE `clazz_student` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `clazz_id`   BIGINT   NOT NULL                COMMENT '班级（物理外键→clazz.id，级联删除）',
    `student_id` BIGINT   NOT NULL                COMMENT '学生（物理外键→sys_user.id；唯一：一个学生至多一个班级）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_clazz_student_student` (`student_id`),
    KEY `idx_clazz_student_clazz` (`clazz_id`),
    CONSTRAINT `fk_cs_clazz` FOREIGN KEY (`clazz_id`) REFERENCES `clazz` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_cs_student` FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级成员（一个学生至多一个班级）';

CREATE TABLE `clazz_course` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `clazz_id`   BIGINT   NOT NULL                COMMENT '班级（物理外键→clazz.id，级联删除）',
    `course_id`  BIGINT   NOT NULL                COMMENT '课程（物理外键→course.id）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关联时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_clazz_course` (`clazz_id`, `course_id`),
    KEY `idx_clazz_course_course` (`course_id`),
    CONSTRAINT `fk_cc_clazz` FOREIGN KEY (`clazz_id`) REFERENCES `clazz` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_cc_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级关联课程';
