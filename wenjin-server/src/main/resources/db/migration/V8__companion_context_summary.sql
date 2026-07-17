-- V8: companion conversation context summary fields for long-history windowing

ALTER TABLE `companion_conversation`
    ADD COLUMN `summary_text` MEDIUMTEXT NULL
        COMMENT '历史消息滚动摘要（送入模型）' AFTER `node_code`,
    ADD COLUMN `summary_through_message_id` BIGINT NULL
        COMMENT '摘要覆盖到的最后一条消息 id（含）' AFTER `summary_text`;
