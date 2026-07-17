-- ════════════════════════════════════════════════════════════════════════
-- quarantine-orphans.sql
-- 将破坏引用完整性的孤儿行软隔离（默认用归档 / 标记表，不硬删业务主键目标）。
-- 执行前务必先跑 preflight-integrity.sql 并备份。
--
-- 约束矩阵（与 preflight-integrity.sql 一致）：
--   · 不物理删除 kg_node / course / question 等被下游引用的主键行
--   · 孤儿边 / 孤儿关联：优先 archived_at 或移入 quarantine 旁路表
--   · Task 19 V9 之前不建物理 FK；本脚本仅应用层一致性修复
-- ════════════════════════════════════════════════════════════════════════

-- 可选：旁路隔离表（若不存在则创建；可重复执行）
CREATE TABLE IF NOT EXISTS `quarantine_orphan_row` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `source_table` VARCHAR(64)  NOT NULL COMMENT '来源表名',
    `source_pk`    BIGINT       NOT NULL COMMENT '来源行主键',
    `reason`       VARCHAR(256) NOT NULL COMMENT '隔离原因',
    `payload_json` JSON         DEFAULT NULL COMMENT '原行快照（可选）',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_source` (`source_table`, `source_pk`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='孤儿行隔离登记';

-- ── 1) 孤儿边：from/to 节点不存在 → 归档边（保留 id，避免误硬删）──────────
UPDATE kg_edge e
LEFT JOIN kg_node f ON f.id = e.from_node_id
LEFT JOIN kg_node t ON t.id = e.to_node_id
SET e.archived_at = COALESCE(e.archived_at, NOW())
WHERE (f.id IS NULL OR t.id IS NULL)
  AND e.archived_at IS NULL;

INSERT INTO quarantine_orphan_row (source_table, source_pk, reason)
SELECT 'kg_edge', e.id, 'orphan edge endpoint missing'
FROM kg_edge e
LEFT JOIN kg_node f ON f.id = e.from_node_id
LEFT JOIN kg_node t ON t.id = e.to_node_id
WHERE f.id IS NULL OR t.id IS NULL
ON DUPLICATE KEY UPDATE reason = VALUES(reason);

-- ── 2) 边 course 与节点 course 不一致 → 归档边 ────────────────────────────
UPDATE kg_edge e
JOIN kg_node f ON f.id = e.from_node_id
JOIN kg_node t ON t.id = e.to_node_id
SET e.archived_at = COALESCE(e.archived_at, NOW())
WHERE (e.course_id <> f.course_id OR e.course_id <> t.course_id OR f.course_id <> t.course_id)
  AND e.archived_at IS NULL;

-- ── 3) 节点指向不存在课程 → 归档节点（不硬删，保留 mastery/question_node 引用）
UPDATE kg_node n
LEFT JOIN course c ON c.id = n.course_id
SET n.archived_at = COALESCE(n.archived_at, NOW())
WHERE c.id IS NULL
  AND n.archived_at IS NULL;

INSERT INTO quarantine_orphan_row (source_table, source_pk, reason)
SELECT 'kg_node', n.id, 'orphan node course missing'
FROM kg_node n
LEFT JOIN course c ON c.id = n.course_id
WHERE c.id IS NULL
ON DUPLICATE KEY UPDATE reason = VALUES(reason);

-- ── 4) question_node 指向不存在节点：登记隔离（默认不删关联，避免题库断链）
INSERT INTO quarantine_orphan_row (source_table, source_pk, reason)
SELECT 'question_node', qn.id, 'question_node.node_id missing'
FROM question_node qn
LEFT JOIN kg_node n ON n.id = qn.node_id
WHERE n.id IS NULL
ON DUPLICATE KEY UPDATE reason = VALUES(reason);

-- ── 5) mastery 指向不存在节点：登记隔离（保留掌握行供审计）
INSERT INTO quarantine_orphan_row (source_table, source_pk, reason)
SELECT 'student_mastery', m.id, 'mastery.node_id missing'
FROM student_mastery m
LEFT JOIN kg_node n ON n.id = m.node_id
WHERE n.id IS NULL
ON DUPLICATE KEY UPDATE reason = VALUES(reason);

-- 说明：若需对 question_node / mastery 做物理清理，须另开变更单并附回滚；
-- 本任务默认仅归档图谱实体 + 登记隔离，禁止静默硬删。
