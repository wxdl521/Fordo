-- ════════════════════════════════════════════════════════════════════════
-- post-migration-verification.sql
-- V9（及当前最终 schema）落地后的只读验收。
-- 期望：物理 FK 数量达标、孤儿/跨课程/角色检查为 0、flyway V9 SUCCESS。
-- ════════════════════════════════════════════════════════════════════════

-- ── 1) Flyway 成功记录（V9 必须 SUCCESS）────────────────────────────────
SELECT 'flyway_v9_success' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END AS bad_count,
       MAX(version) AS detail_version,
       MAX(success) AS detail_success
FROM flyway_schema_history
WHERE version = '9' AND success = 1;

SELECT version, description, success, installed_on, checksum
FROM flyway_schema_history
WHERE success = 1
ORDER BY installed_rank;

-- ── 2) 精确物理 FK 矩阵（V9 新增 42 个 + V3 既有 2 个 + V5 既有 1 个）──
-- 每个预期 table/constraint 返回一行；所有 bad_count 必须为 0。
SELECT CONCAT('fk_present_', expected.constraint_name) AS check_id,
       CASE WHEN actual.constraint_name IS NULL THEN 1 ELSE 0 END AS bad_count,
       expected.table_name AS detail_table,
       expected.constraint_name AS detail_constraint
FROM (
    SELECT 'course' AS table_name, 'fk_course_teacher' AS constraint_name
    UNION ALL SELECT 'kg_node', 'fk_node_course'
    UNION ALL SELECT 'kg_edge', 'fk_edge_course'
    UNION ALL SELECT 'kg_edge', 'fk_edge_from_node'
    UNION ALL SELECT 'kg_edge', 'fk_edge_to_node'
    UNION ALL SELECT 'question', 'fk_question_course'
    UNION ALL SELECT 'question_option', 'fk_option_question'
    UNION ALL SELECT 'question_node', 'fk_qn_question'
    UNION ALL SELECT 'question_node', 'fk_qn_node'
    UNION ALL SELECT 'student_mastery', 'fk_mastery_student'
    UNION ALL SELECT 'student_mastery', 'fk_mastery_course'
    UNION ALL SELECT 'student_mastery', 'fk_mastery_node'
    UNION ALL SELECT 'mastery_snapshot', 'fk_snapshot_student'
    UNION ALL SELECT 'mastery_snapshot', 'fk_snapshot_course'
    UNION ALL SELECT 'mastery_snapshot', 'fk_snapshot_node'
    UNION ALL SELECT 'learning_path', 'fk_path_student'
    UNION ALL SELECT 'learning_path', 'fk_path_course'
    UNION ALL SELECT 'learning_path', 'fk_path_target_node'
    UNION ALL SELECT 'learning_path_item', 'fk_path_item_path'
    UNION ALL SELECT 'learning_path_item', 'fk_path_item_node'
    UNION ALL SELECT 'practice_session', 'fk_practice_student'
    UNION ALL SELECT 'practice_session', 'fk_practice_course'
    UNION ALL SELECT 'practice_session', 'fk_practice_node'
    UNION ALL SELECT 'practice_session', 'fk_practice_path_item'
    UNION ALL SELECT 'adaptive_diag_session', 'fk_adaptive_student'
    UNION ALL SELECT 'adaptive_diag_session', 'fk_adaptive_course'
    UNION ALL SELECT 'answer_record', 'fk_answer_student'
    UNION ALL SELECT 'answer_record', 'fk_answer_course'
    UNION ALL SELECT 'answer_record', 'fk_answer_question'
    UNION ALL SELECT 'answer_record', 'fk_answer_fixed_diag'
    UNION ALL SELECT 'node_resource', 'fk_node_resource_node'
    UNION ALL SELECT 'node_resource', 'fk_node_resource_resource'
    UNION ALL SELECT 'student_course', 'fk_sc_student'
    UNION ALL SELECT 'student_course', 'fk_sc_course'
    UNION ALL SELECT 'student_course', 'fk_sc_current_path'
    UNION ALL SELECT 'companion_conversation', 'fk_companion_student'
    UNION ALL SELECT 'companion_conversation', 'fk_companion_course'
    UNION ALL SELECT 'companion_message', 'fk_companion_msg_conv'
    UNION ALL SELECT 'extraction_review', 'fk_extraction_course'
    UNION ALL SELECT 'ai_job', 'fk_ai_job_owner'
    UNION ALL SELECT 'ai_job', 'fk_ai_job_course'
    UNION ALL SELECT 'diagnostic_session', 'fk_diag_student'
    UNION ALL SELECT 'diagnostic_session', 'fk_diag_course'
    UNION ALL SELECT 'diagnostic_session_question', 'fk_dsq_session'
    UNION ALL SELECT 'diagnostic_session_question', 'fk_dsq_question_course'
) expected
LEFT JOIN information_schema.TABLE_CONSTRAINTS actual
  ON actual.constraint_schema = DATABASE()
 AND actual.constraint_type = 'FOREIGN KEY'
 AND actual.table_name = expected.table_name
 AND actual.constraint_name = expected.constraint_name
ORDER BY expected.table_name, expected.constraint_name;

SELECT constraint_name, table_name
FROM information_schema.TABLE_CONSTRAINTS
WHERE constraint_schema = DATABASE()
  AND constraint_type = 'FOREIGN KEY'
ORDER BY table_name, constraint_name;

-- ── 3) 列契约：teacher_id / stem_hash NOT NULL；role CHECK ─────────────
SELECT 'course_teacher_id_nullable' AS check_id,
       CASE WHEN IS_NULLABLE = 'NO' THEN 0 ELSE 1 END AS bad_count
FROM information_schema.COLUMNS
WHERE table_schema = DATABASE()
  AND table_name = 'course'
  AND column_name = 'teacher_id';

SELECT 'question_stem_hash_nullable' AS check_id,
       CASE WHEN IS_NULLABLE = 'NO' THEN 0 ELSE 1 END AS bad_count
FROM information_schema.COLUMNS
WHERE table_schema = DATABASE()
  AND table_name = 'question'
  AND column_name = 'stem_hash';

SELECT 'chk_sys_user_role_present' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END AS bad_count
FROM information_schema.TABLE_CONSTRAINTS
WHERE constraint_schema = DATABASE()
  AND table_name = 'sys_user'
  AND constraint_name = 'chk_sys_user_role'
  AND constraint_type = 'CHECK';

SELECT CONCAT(expected.table_name, '_archive_marker_column') AS check_id,
       CASE WHEN actual.column_name IS NOT NULL
                  AND actual.is_nullable = 'NO'
                  AND actual.column_default = '0'
            THEN 0 ELSE 1 END AS bad_count
FROM (
    SELECT 'kg_node' AS table_name
    UNION ALL SELECT 'kg_edge'
) expected
LEFT JOIN information_schema.COLUMNS actual
  ON actual.table_schema = DATABASE()
 AND actual.table_name = expected.table_name
 AND actual.column_name = 'archived_by_course';

SELECT CONCAT('check_present_', expected.constraint_name) AS check_id,
       CASE WHEN actual.constraint_name IS NULL THEN 1 ELSE 0 END AS bad_count
FROM (
    SELECT 'kg_node' AS table_name, 'chk_node_course_archive_marker' AS constraint_name
    UNION ALL SELECT 'kg_edge', 'chk_edge_course_archive_marker'
) expected
LEFT JOIN information_schema.TABLE_CONSTRAINTS actual
  ON actual.constraint_schema = DATABASE()
 AND actual.table_name = expected.table_name
 AND actual.constraint_name = expected.constraint_name
 AND actual.constraint_type = 'CHECK';

-- ── 4) 零孤儿 / 零跨课程 / 零非法 role（抽查，应全 0）────────────────
SELECT 'verify_orphan_node_course' AS check_id, COUNT(*) AS bad_count
FROM kg_node n LEFT JOIN course c ON c.id = n.course_id WHERE c.id IS NULL;

SELECT 'verify_orphan_edge_endpoints' AS check_id, COUNT(*) AS bad_count
FROM kg_edge e
LEFT JOIN kg_node f ON f.id = e.from_node_id
LEFT JOIN kg_node t ON t.id = e.to_node_id
WHERE f.id IS NULL OR t.id IS NULL;

SELECT 'verify_cross_course_qn' AS check_id, COUNT(*) AS bad_count
FROM question_node qn
JOIN question q ON q.id = qn.question_id
JOIN kg_node n ON n.id = qn.node_id
WHERE q.course_id <> n.course_id;

SELECT 'verify_cross_course_mastery' AS check_id, COUNT(*) AS bad_count
FROM student_mastery m
JOIN kg_node n ON n.id = m.node_id
WHERE m.course_id <> n.course_id;

SELECT 'verify_cross_course_snapshot' AS check_id, COUNT(*) AS bad_count
FROM mastery_snapshot s
JOIN kg_node n ON n.id = s.node_id
WHERE s.course_id <> n.course_id;

SELECT 'verify_practice_path_item_scope' AS check_id, COUNT(*) AS bad_count
FROM practice_session ps
JOIN learning_path_item i ON i.id = ps.path_item_id
JOIN learning_path lp ON lp.id = i.path_id
WHERE ps.path_item_id IS NOT NULL
  AND (ps.student_id <> lp.student_id
    OR ps.course_id <> lp.course_id
    OR ps.node_id <> i.node_id);

SELECT 'verify_answer_fixed_diag_scope' AS check_id, COUNT(*) AS bad_count
FROM answer_record ar
JOIN diagnostic_session ds ON ds.id = ar.fixed_diagnostic_session_id
WHERE ar.fixed_diagnostic_session_id IS NOT NULL
  AND (ar.student_id <> ds.student_id OR ar.course_id <> ds.course_id);

SELECT 'verify_unknown_role' AS check_id, COUNT(*) AS bad_count
FROM sys_user WHERE role IS NULL OR role NOT IN (1, 2, 3);

SELECT 'verify_null_teacher_id' AS check_id, COUNT(*) AS bad_count
FROM course WHERE teacher_id IS NULL;

SELECT 'verify_null_stem_hash' AS check_id, COUNT(*) AS bad_count
FROM question WHERE stem_hash IS NULL;

SELECT 'verify_invalid_node_archive_marker' AS check_id, COUNT(*) AS bad_count
FROM kg_node n
JOIN course c ON c.id = n.course_id
WHERE n.archived_by_course NOT IN (0, 1)
   OR (n.archived_by_course = 1 AND n.archived_at IS NULL)
   OR (n.archived_by_course = 1 AND c.archived_at IS NULL)
   OR (c.archived_at IS NOT NULL AND n.archived_at IS NULL);

SELECT 'verify_invalid_edge_archive_marker' AS check_id, COUNT(*) AS bad_count
FROM kg_edge e
JOIN course c ON c.id = e.course_id
WHERE e.archived_by_course NOT IN (0, 1)
   OR (e.archived_by_course = 1 AND e.archived_at IS NULL)
   OR (e.archived_by_course = 1 AND c.archived_at IS NULL)
   OR (c.archived_at IS NOT NULL AND e.archived_at IS NULL);

SELECT 'verify_dup_stem_hash' AS check_id, COUNT(*) AS bad_count
FROM (
    SELECT course_id, stem_hash FROM question
    WHERE stem_hash IS NOT NULL
    GROUP BY course_id, stem_hash HAVING COUNT(*) > 1
) d;

SELECT 'verify_multi_active_paths' AS check_id, COUNT(*) AS bad_count
FROM (
    SELECT student_id, course_id FROM learning_path
    WHERE status = 1
    GROUP BY student_id, course_id HAVING COUNT(*) > 1
) d;

-- 全部 bad_count 应为 0 才可标记 release 成功。
