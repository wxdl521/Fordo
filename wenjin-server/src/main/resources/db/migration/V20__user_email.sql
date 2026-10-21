-- V20: 注册邮箱（存量用户可空；新注册唯一）
ALTER TABLE `sys_user`
    ADD COLUMN `email` VARCHAR(255) DEFAULT NULL COMMENT '注册邮箱' AFTER `real_name`;
CREATE UNIQUE INDEX `uk_sys_user_email` ON `sys_user` (`email`);
