-- V13: RAG corpus tables (M8) — doc_source / doc_chunk / chunk_node_map
-- FULLTEXT uses ngram parser (requires ngram_token_size=2; see ops/sql/preflight-ngram.sql)

CREATE TABLE `doc_source` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `course_id`  BIGINT       NOT NULL COMMENT '课程（逻辑外键→course.id）',
    `title`      VARCHAR(200) NOT NULL COMMENT '语料标题',
    `kind`       TINYINT      NOT NULL COMMENT '0=COURSEWARE,1=TEXTBOOK,2=NODE_REF',
    `file_hash`  CHAR(64)     NULL COMMENT '内容寻址 hash（复用 AiJobPayloadStore）',
    `status`     TINYINT      NOT NULL DEFAULT 0 COMMENT '0=INGESTING,1=READY,2=FAILED,3=ARCHIVED',
    `created_by` BIGINT       NOT NULL COMMENT '创建者（逻辑外键→sys_user.id）',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_source_course` (`course_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='语料来源（M8）';

CREATE TABLE `doc_chunk` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `source_id`  BIGINT       NOT NULL COMMENT '来源（逻辑外键→doc_source.id）',
    `seq`        INT          NOT NULL COMMENT '块序号（同 source 内从 0 递增）',
    `content`    TEXT         NOT NULL COMMENT '切块正文',
    `char_count` INT          NOT NULL COMMENT '字符数',
    `meta_json`  VARCHAR(500) NULL COMMENT '标题路径/页码等元数据 JSON',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk_seq` (`source_id`, `seq`),
    FULLTEXT KEY `ft_chunk_content` (`content`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='语料切块（M8）';

CREATE TABLE `chunk_node_map` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `chunk_id`      BIGINT       NOT NULL COMMENT '切块（逻辑外键→doc_chunk.id）',
    `course_id`     BIGINT       NOT NULL COMMENT '课程（逻辑外键→course.id）',
    `node_code`     VARCHAR(32)  NOT NULL COMMENT '知识点 node_code',
    `confidence`    DECIMAL(4,3) NOT NULL DEFAULT 1.000 COMMENT '映射置信度',
    `review_status` TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING,1=APPROVED,2=REJECTED',
    `reviewed_by`   BIGINT       NULL COMMENT '审核人（可空）',
    `reviewed_at`   DATETIME     NULL COMMENT '审核时间（可空）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_map_chunk_node` (`chunk_id`, `node_code`),
    KEY `idx_map_node` (`course_id`, `node_code`, `review_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='chunk↔节点映射（M8）';
