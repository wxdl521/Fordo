-- ════════════════════════════════════════════════════════════════════════
-- preflight-ngram.sql
-- M8 RAG 中文全文检索预检（只读 SELECT）。
-- FULLTEXT WITH PARSER ngram 要求 ngram_token_size=2（MySQL 默认常为 2，
-- 但实例若改为 1 会破坏中文二元组匹配）。在 migrator / 运维会话执行。
-- 任一条 check 失败应先调参再跑 V13 或打开 rag-enabled 生产流量。
-- ════════════════════════════════════════════════════════════════════════

-- 1) ngram 插件是否可用
SELECT 'ngram_plugin' AS check_id,
       PLUGIN_NAME,
       PLUGIN_STATUS
FROM information_schema.PLUGINS
WHERE PLUGIN_NAME = 'ngram';

-- 2) ngram_token_size（期望 2）
SHOW VARIABLES LIKE 'ngram_token_size';

-- 3) 期望断言辅助：应用侧脚本可解析 token size
SELECT 'ngram_token_size_ok' AS check_id,
       CASE
           WHEN @@global.ngram_token_size = 2 THEN 1
           ELSE 0
       END AS ok,
       @@global.ngram_token_size AS ngram_token_size;

-- 4) 若已迁移 V13：确认 FULLTEXT 索引使用 ngram 解析器
SELECT 'ft_chunk_content_ngram' AS check_id,
       INDEX_NAME,
       INDEX_TYPE,
       INDEX_COMMENT
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'doc_chunk'
  AND INDEX_NAME = 'ft_chunk_content';
