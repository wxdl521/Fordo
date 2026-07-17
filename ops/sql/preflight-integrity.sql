-- ════════════════════════════════════════════════════════════════════════
-- preflight-integrity.sql
-- 图谱归档 / 主键差量更新上线前的完整性预检（只读 SELECT）。
-- 在 migrator 或只读副本执行；任一条 count > 0 应人工评估后再切写路径。
-- 本文件仅覆盖图谱专项；V9 前必须同时执行 security-role-audit.sql。
--
-- 约束矩阵（逻辑外键；Task 19 V9 前不建物理 FK）：
--   course.id          ← kg_node.course_id, kg_edge.course_id, question.course_id,
--                        student_course.course_id, student_mastery.course_id,
--                        learning_path.course_id, diagnostic_session.course_id, …
--   kg_node.id         ← kg_edge.from_node_id / to_node_id,
--                        question_node.node_id, student_mastery.node_id,
--                        learning_path_item.node_id, …
--   kg_edge            业务唯一 (course_id, from_node_id, to_node_id, relation_type)
--   question.id        ← question_node.question_id, question_option.question_id,
--                        answer_record.question_id, …
--
-- 归档语义：archived_at IS NULL = 生效；非 NULL = 软归档（主键保留，下游 FK 仍可指向）。
-- archived_by_course=1 仅表示课程归档级联；独立归档必须保持 0。
-- 差量导入键：节点 (course_id, node_code)；边 (course_id, from_node_id, to_node_id, relation_type)。
-- ════════════════════════════════════════════════════════════════════════

-- 1) 节点指向不存在的课程
SELECT 'orphan_node_course' AS check_id, COUNT(*) AS bad_count
FROM kg_node n
LEFT JOIN course c ON c.id = n.course_id
WHERE c.id IS NULL;

-- 2) 边指向不存在的课程
SELECT 'orphan_edge_course' AS check_id, COUNT(*) AS bad_count
FROM kg_edge e
LEFT JOIN course c ON c.id = e.course_id
WHERE c.id IS NULL;

-- 3) 边 from/to 节点不存在
SELECT 'orphan_edge_from' AS check_id, COUNT(*) AS bad_count
FROM kg_edge e
LEFT JOIN kg_node n ON n.id = e.from_node_id
WHERE n.id IS NULL;

SELECT 'orphan_edge_to' AS check_id, COUNT(*) AS bad_count
FROM kg_edge e
LEFT JOIN kg_node n ON n.id = e.to_node_id
WHERE n.id IS NULL;

-- 4) 边两端节点不在同一课程（或与边的 course_id 不一致）
SELECT 'edge_node_course_mismatch' AS check_id, COUNT(*) AS bad_count
FROM kg_edge e
JOIN kg_node f ON f.id = e.from_node_id
JOIN kg_node t ON t.id = e.to_node_id
WHERE e.course_id <> f.course_id
   OR e.course_id <> t.course_id
   OR f.course_id <> t.course_id;

-- 5) question_node 指向不存在的节点 / 题目
SELECT 'orphan_qn_node' AS check_id, COUNT(*) AS bad_count
FROM question_node qn
LEFT JOIN kg_node n ON n.id = qn.node_id
WHERE n.id IS NULL;

SELECT 'orphan_qn_question' AS check_id, COUNT(*) AS bad_count
FROM question_node qn
LEFT JOIN question q ON q.id = qn.question_id
WHERE q.id IS NULL;

-- 6) 掌握度指向不存在的节点 / 课程
SELECT 'orphan_mastery_node' AS check_id, COUNT(*) AS bad_count
FROM student_mastery m
LEFT JOIN kg_node n ON n.id = m.node_id
WHERE n.id IS NULL;

SELECT 'orphan_mastery_course' AS check_id, COUNT(*) AS bad_count
FROM student_mastery m
LEFT JOIN course c ON c.id = m.course_id
WHERE c.id IS NULL;

-- 7) 路径步骤指向不存在的节点
SELECT 'orphan_path_item_node' AS check_id, COUNT(*) AS bad_count
FROM learning_path_item i
LEFT JOIN kg_node n ON n.id = i.node_id
WHERE n.id IS NULL;

-- 8) 重复业务键（应被 uk 挡住；若有则为历史脏数据）
SELECT 'dup_node_code' AS check_id, COUNT(*) AS bad_count
FROM (
    SELECT course_id, node_code, COUNT(*) c
    FROM kg_node
    GROUP BY course_id, node_code
    HAVING c > 1
) t;

SELECT 'dup_edge_key' AS check_id, COUNT(*) AS bad_count
FROM (
    SELECT course_id, from_node_id, to_node_id, relation_type, COUNT(*) c
    FROM kg_edge
    GROUP BY course_id, from_node_id, to_node_id, relation_type
    HAVING c > 1
) t;

-- 9) 课程级联归档来源标记必须与课程/图行状态一致
SELECT 'invalid_node_archive_marker' AS check_id, COUNT(*) AS bad_count
FROM kg_node n
JOIN course c ON c.id = n.course_id
WHERE n.archived_by_course NOT IN (0, 1)
   OR (n.archived_by_course = 1 AND n.archived_at IS NULL)
   OR (n.archived_by_course = 1 AND c.archived_at IS NULL)
   OR (c.archived_at IS NOT NULL AND n.archived_at IS NULL);

SELECT 'invalid_edge_archive_marker' AS check_id, COUNT(*) AS bad_count
FROM kg_edge e
JOIN course c ON c.id = e.course_id
WHERE e.archived_by_course NOT IN (0, 1)
   OR (e.archived_by_course = 1 AND e.archived_at IS NULL)
   OR (e.archived_by_course = 1 AND c.archived_at IS NULL)
   OR (c.archived_at IS NOT NULL AND e.archived_at IS NULL);
