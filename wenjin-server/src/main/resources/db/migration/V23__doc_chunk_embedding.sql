-- V23: RAG 切块向量（嵌入基础设施，方向 5 / R1）
CREATE TABLE IF NOT EXISTS `doc_chunk_embedding` (
    `chunk_id`    BIGINT      NOT NULL COMMENT '切块（逻辑外键→doc_chunk.id）',
    `model`       VARCHAR(64) NOT NULL COMMENT '嵌入模型标识，如 embedding-3@1024',
    `dim`         INT         NOT NULL COMMENT '向量维度',
    `vector`      MEDIUMBLOB  NOT NULL COMMENT 'float32 little-endian',
    `content_sha` CHAR(64)    NOT NULL COMMENT '切块内容 SHA-256，内容未变则跳过重嵌',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '嵌入时间',
    PRIMARY KEY (`chunk_id`),
    KEY `idx_chunk_emb_model` (`model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='语料切块向量';
