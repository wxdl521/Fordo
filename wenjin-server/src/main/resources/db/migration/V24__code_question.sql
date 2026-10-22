-- V24: 编程题题型（方向 6 / C2）：question 增 starter_code / code_language，新增 question_code_case 测试用例表
ALTER TABLE `question`
    ADD COLUMN `starter_code`  TEXT        NULL COMMENT '编程题起始代码（可空；仅 type=5）' AFTER `analysis`,
    ADD COLUMN `code_language` VARCHAR(16) NULL DEFAULT 'java' COMMENT '编程题语言（v1 仅 java）' AFTER `starter_code`;

CREATE TABLE IF NOT EXISTS `question_code_case` (
    `id`              BIGINT     NOT NULL AUTO_INCREMENT,
    `question_id`     BIGINT     NOT NULL COMMENT '所属编程题（逻辑外键→question.id）',
    `seq`             INT        NOT NULL COMMENT '用例序号（从 1 起，题内唯一）',
    `stdin`           TEXT       NULL COMMENT '标准输入',
    `expected_stdout` TEXT       NULL COMMENT '期望标准输出',
    `hidden`          TINYINT    NOT NULL DEFAULT 0 COMMENT '1=隐藏用例（学生侧不可见输入/期望/实际）',
    `created_at`      DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_case_question_seq` (`question_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编程题测试用例';
