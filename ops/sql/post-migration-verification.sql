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

-- ── 2) 物理 FK 计数（V9 矩阵 + 既有 V3/V5）────────────────────────────
-- 期望至少包含下列命名约束（information_schema）
SELECT 'fk_named_present' AS check_id,
       CASE WHEN COUNT(*) >= 40 THEN 0 ELSE 1 END AS bad_count,
       COUNT(*) AS fk_count
FROM information_schema.TABLE_CONSTRAINTS
WHERE constraint_schema = DATABASE()
  AND constraint_type = 'FOREIGN KEY'
  AND constraint_name IN (
      'fk_course_teacher',
      'fk_node_course',
      'fk_edge_course', 'fk_edge_from_node', 'fk_edge_to_node',
      'fk_question_course',
      'fk_option_question',
      'fk_qn_question', 'fk_qn_node',
      'fk_mastery_student', 'fk_mastery_course', 'fk_mastery_node',
      'fk_snapshot_student', 'fk_snapshot_course', 'fk_snapshot_node',
      'fk_path_student', 'fk_path_course', 'fk_path_target_node',
      'fk_path_item_path', 'fk_path_item_node',
      'fk_practice_student', 'fk_practice_course', 'fk_practice_node', 'fk_practice_path_item',
      'fk_adaptive_student', 'fk_adaptive_course',
      'fk_answer_student', 'fk_answer_course', 'fk_answer_question',
      'fk_answer_practice_session', 'fk_answer_fixed_diag',
      'fk_node_resource_node', 'fk_node_resource_resource',
      'fk_sc_student', 'fk_sc_course', 'fk_sc_current_path',
      'fk_companion_student', 'fk_companion_course', 'fk_companion_msg_conv',
      'fk_extraction_course',
      'fk_ai_job_owner', 'fk_ai_job_course',
      'fk_diag_student', 'fk_diag_course',
      'fk_dsq_session', 'fk_dsq_question_course'
  );

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

SELECT 'verify_unknown_role' AS check_id, COUNT(*) AS bad_count
FROM sys_user WHERE role IS NULL OR role NOT IN (1, 2, 3);

SELECT 'verify_null_teacher_id' AS check_id, COUNT(*) AS bad_count
FROM course WHERE teacher_id IS NULL;

SELECT 'verify_null_stem_hash' AS check_id, COUNT(*) AS bad_count
FROM question WHERE stem_hash IS NULL;

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
