-- ════════════════════════════════════════════════════════════════════════
--  问津 · 演示种子数据（增量 INSERT，供 schema.sql 建表后单独加载）
--  本地开发：先执行 schema.sql，再执行本脚本。
--  生产环境：仅执行 schema.sql，勿加载本脚本。
-- ════════════════════════════════════════════════════════════════════════

-- 演示教师（id=1）；初始密码 demo，首次登录自动升级为 BCrypt
INSERT IGNORE INTO `sys_user` (`id`, `username`, `password`, `real_name`, `role`, `status`)
VALUES (1, 'demo_teacher', 'demo', '演示教师', 1, 1);

-- 演示课程（id=1）；前端染色地图默认按 courseId=1 查询
INSERT IGNORE INTO `course` (`id`, `code`, `name`, `description`, `teacher_id`, `status`)
VALUES (1, '52015CC4B4', '软件工程', '问津演示课程 · 软件工程知识图谱', 1, 1);

-- 演示学生（id=2）；初始密码 demo
INSERT IGNORE INTO `sys_user` (`id`, `username`, `password`, `real_name`, `role`, `status`)
VALUES (2, 'demo_student', 'demo', '演示学生', 2, 1);

-- 演示学生选课：demo_student (id=2) 选了软件工程 (course_id=1)
INSERT IGNORE INTO `student_course` (`student_id`, `course_id`)
VALUES (2, 1);