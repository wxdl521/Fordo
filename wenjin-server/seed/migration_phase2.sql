USE `wenjin`;
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，若已执行过会报 1060，可忽略
ALTER TABLE `question` ADD COLUMN `status` TINYINT NOT NULL DEFAULT 0
    COMMENT '审核状态：0=待审核,1=已通过,2=已驳回【阶段二补充】' AFTER `source`;
ALTER TABLE `question_option` ADD COLUMN `point_node_code` VARCHAR(32) DEFAULT NULL
    COMMENT 'distractor_map：该错误选项暴露的薄弱 node_code【阶段二补充】' AFTER `is_correct`;
INSERT INTO `sys_user` (id, username, password, real_name, role, status)
    VALUES (2, 'demo_student', 'demo', '演示学生', 2, 1)
    ON DUPLICATE KEY UPDATE real_name=VALUES(real_name);
