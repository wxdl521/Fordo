-- ════════════════════════════════════════════════════════════════════════
-- security-role-audit.sql
-- 上线前 / V9 前只读数据审计（SELECT only）。
-- 在 migrator 或只读副本执行。任一条 bad_count > 0 都阻断 V9；inventory_count / review_count 仅需记录人工复核结论。
--
-- 覆盖：
--   · 疑似公开注册产生的教师账号
--   · 课程 owner 为 NULL / 悬空 / 非 TEACHER|ADMIN
--   · 未知 role 值
--   · 逻辑外键孤儿
--   · 跨课程 question_node / mastery / path / practice 错配
--   · 重复作答
--   · 多条有效学习路径
--   · 重复 (course_id, stem_hash)
-- ════════════════════════════════════════════════════════════════════════

-- ── 1) 疑似公开注册教师（role=TEACHER 账号清单类；非零须人工确认开通路径）──
-- 1a) 全部教师账号（基线盘点）
SELECT 'teacher_accounts_inventory' AS check_id, COUNT(*) AS inventory_count
FROM sys_user u
WHERE u.role = 1;

-- 1b) 教师无任何名下课程（可能是自助提权探测 / 未完成开通）
SELECT 'suspected_teacher_no_courses' AS check_id, COUNT(*) AS review_count
FROM sys_user u
WHERE u.role = 1
  AND NOT EXISTS (SELECT 1 FROM course c WHERE c.teacher_id = u.id AND c.archived_at IS NULL);

-- 1c) 教师同时又有选课/作答学生活动（双身份异常）
SELECT 'suspected_teacher_with_student_activity' AS check_id, COUNT(*) AS review_count
FROM sys_user u
WHERE u.role = 1
  AND (
        EXISTS (SELECT 1 FROM student_course sc WHERE sc.student_id = u.id)
     OR EXISTS (SELECT 1 FROM answer_record ar WHERE ar.student_id = u.id)
      );

-- ── 2) 课程 owner：NULL / 悬空 / 非 TEACHER(1)|ADMIN(3) ───────────────────
SELECT 'course_owner_null' AS check_id, COUNT(*) AS bad_count
FROM course c
WHERE c.teacher_id IS NULL;

SELECT 'course_owner_dangling' AS check_id, COUNT(*) AS bad_count
FROM course c
LEFT JOIN sys_user u ON u.id = c.teacher_id
WHERE c.teacher_id IS NOT NULL
  AND u.id IS NULL;

SELECT 'course_owner_not_teacher_or_admin' AS check_id, COUNT(*) AS bad_count
FROM course c
JOIN sys_user u ON u.id = c.teacher_id
WHERE u.role NOT IN (1, 3)
  AND c.archived_at IS NULL;

-- ── 3) 未知 role 值（仅允许 1=教师, 2=学生, 3=管理员）────────────────────
SELECT 'unknown_user_role' AS check_id, COUNT(*) AS bad_count
FROM sys_user u
WHERE u.role IS NULL
   OR u.role NOT IN (1, 2, 3);

-- ── 4) 逻辑外键孤儿（与 preflight-integrity 对齐并扩展）──────────────────
SELECT 'orphan_node_course' AS check_id, COUNT(*) AS bad_count
FROM kg_node n
LEFT JOIN course c ON c.id = n.course_id
WHERE c.id IS NULL;

SELECT 'orphan_edge_course' AS check_id, COUNT(*) AS bad_count
FROM kg_edge e
LEFT JOIN course c ON c.id = e.course_id
WHERE c.id IS NULL;

SELECT 'orphan_edge_from' AS check_id, COUNT(*) AS bad_count
FROM kg_edge e
LEFT JOIN kg_node n ON n.id = e.from_node_id
WHERE n.id IS NULL;

SELECT 'orphan_edge_to' AS check_id, COUNT(*) AS bad_count
FROM kg_edge e
LEFT JOIN kg_node n ON n.id = e.to_node_id
WHERE n.id IS NULL;

SELECT 'orphan_question_course' AS check_id, COUNT(*) AS bad_count
FROM question q
LEFT JOIN course c ON c.id = q.course_id
WHERE c.id IS NULL;

SELECT 'orphan_option_question' AS check_id, COUNT(*) AS bad_count
FROM question_option o
LEFT JOIN question q ON q.id = o.question_id
WHERE q.id IS NULL;

SELECT 'orphan_qn_question' AS check_id, COUNT(*) AS bad_count
FROM question_node qn
LEFT JOIN question q ON q.id = qn.question_id
WHERE q.id IS NULL;

SELECT 'orphan_qn_node' AS check_id, COUNT(*) AS bad_count
FROM question_node qn
LEFT JOIN kg_node n ON n.id = qn.node_id
WHERE n.id IS NULL;

SELECT 'orphan_mastery_student' AS check_id, COUNT(*) AS bad_count
FROM student_mastery m
LEFT JOIN sys_user u ON u.id = m.student_id
WHERE u.id IS NULL;

SELECT 'orphan_mastery_course' AS check_id, COUNT(*) AS bad_count
FROM student_mastery m
LEFT JOIN course c ON c.id = m.course_id
WHERE c.id IS NULL;

SELECT 'orphan_mastery_node' AS check_id, COUNT(*) AS bad_count
FROM student_mastery m
LEFT JOIN kg_node n ON n.id = m.node_id
WHERE n.id IS NULL;

SELECT 'orphan_snapshot_student' AS check_id, COUNT(*) AS bad_count
FROM mastery_snapshot s
LEFT JOIN sys_user u ON u.id = s.student_id
WHERE u.id IS NULL;

SELECT 'orphan_snapshot_course' AS check_id, COUNT(*) AS bad_count
FROM mastery_snapshot s
LEFT JOIN course c ON c.id = s.course_id
WHERE c.id IS NULL;

SELECT 'orphan_snapshot_node' AS check_id, COUNT(*) AS bad_count
FROM mastery_snapshot s
LEFT JOIN kg_node n ON n.id = s.node_id
WHERE n.id IS NULL;

SELECT 'orphan_path_student' AS check_id, COUNT(*) AS bad_count
FROM learning_path lp
LEFT JOIN sys_user u ON u.id = lp.student_id
WHERE u.id IS NULL;

SELECT 'orphan_path_course' AS check_id, COUNT(*) AS bad_count
FROM learning_path lp
LEFT JOIN course c ON c.id = lp.course_id
WHERE c.id IS NULL;

SELECT 'orphan_path_target_node' AS check_id, COUNT(*) AS bad_count
FROM learning_path lp
LEFT JOIN kg_node n ON n.id = lp.target_node_id
WHERE lp.target_node_id IS NOT NULL
  AND n.id IS NULL;

SELECT 'orphan_path_item_path' AS check_id, COUNT(*) AS bad_count
FROM learning_path_item i
LEFT JOIN learning_path lp ON lp.id = i.path_id
WHERE lp.id IS NULL;

SELECT 'orphan_path_item_node' AS check_id, COUNT(*) AS bad_count
FROM learning_path_item i
LEFT JOIN kg_node n ON n.id = i.node_id
WHERE n.id IS NULL;

SELECT 'orphan_practice_student' AS check_id, COUNT(*) AS bad_count
FROM practice_session ps
LEFT JOIN sys_user u ON u.id = ps.student_id
WHERE u.id IS NULL;

SELECT 'orphan_practice_course' AS check_id, COUNT(*) AS bad_count
FROM practice_session ps
LEFT JOIN course c ON c.id = ps.course_id
WHERE c.id IS NULL;

SELECT 'orphan_practice_node' AS check_id, COUNT(*) AS bad_count
FROM practice_session ps
LEFT JOIN kg_node n ON n.id = ps.node_id
WHERE n.id IS NULL;

SELECT 'orphan_practice_path_item' AS check_id, COUNT(*) AS bad_count
FROM practice_session ps
LEFT JOIN learning_path_item i ON i.id = ps.path_item_id
WHERE ps.path_item_id IS NOT NULL
  AND i.id IS NULL;

SELECT 'orphan_adaptive_student' AS check_id, COUNT(*) AS bad_count
FROM adaptive_diag_session ads
LEFT JOIN sys_user u ON u.id = ads.student_id
WHERE u.id IS NULL;

SELECT 'orphan_adaptive_course' AS check_id, COUNT(*) AS bad_count
FROM adaptive_diag_session ads
LEFT JOIN course c ON c.id = ads.course_id
WHERE c.id IS NULL;

SELECT 'orphan_answer_student' AS check_id, COUNT(*) AS bad_count
FROM answer_record ar
LEFT JOIN sys_user u ON u.id = ar.student_id
WHERE u.id IS NULL;

SELECT 'orphan_answer_course' AS check_id, COUNT(*) AS bad_count
FROM answer_record ar
LEFT JOIN course c ON c.id = ar.course_id
WHERE c.id IS NULL;

SELECT 'orphan_answer_question' AS check_id, COUNT(*) AS bad_count
FROM answer_record ar
LEFT JOIN question q ON q.id = ar.question_id
WHERE q.id IS NULL;

SELECT 'orphan_answer_fixed_diag' AS check_id, COUNT(*) AS bad_count
FROM answer_record ar
LEFT JOIN diagnostic_session ds ON ds.id = ar.fixed_diagnostic_session_id
WHERE ar.fixed_diagnostic_session_id IS NOT NULL
  AND ds.id IS NULL;

SELECT 'orphan_node_resource_node' AS check_id, COUNT(*) AS bad_count
FROM node_resource nr
LEFT JOIN kg_node n ON n.id = nr.node_id
WHERE n.id IS NULL;

SELECT 'orphan_node_resource_resource' AS check_id, COUNT(*) AS bad_count
FROM node_resource nr
LEFT JOIN resource r ON r.id = nr.resource_id
WHERE r.id IS NULL;

SELECT 'orphan_sc_student' AS check_id, COUNT(*) AS bad_count
FROM student_course sc
LEFT JOIN sys_user u ON u.id = sc.student_id
WHERE u.id IS NULL;

SELECT 'orphan_sc_course' AS check_id, COUNT(*) AS bad_count
FROM student_course sc
LEFT JOIN course c ON c.id = sc.course_id
WHERE c.id IS NULL;

SELECT 'orphan_sc_current_path' AS check_id, COUNT(*) AS bad_count
FROM student_course sc
LEFT JOIN learning_path lp ON lp.id = sc.current_path_id
WHERE sc.current_path_id IS NOT NULL
  AND lp.id IS NULL;

SELECT 'orphan_companion_student' AS check_id, COUNT(*) AS bad_count
FROM companion_conversation cc
LEFT JOIN sys_user u ON u.id = cc.student_id
WHERE u.id IS NULL;

SELECT 'orphan_companion_course' AS check_id, COUNT(*) AS bad_count
FROM companion_conversation cc
LEFT JOIN course c ON c.id = cc.course_id
WHERE c.id IS NULL;

SELECT 'orphan_companion_msg_conv' AS check_id, COUNT(*) AS bad_count
FROM companion_message cm
LEFT JOIN companion_conversation cc ON cc.id = cm.conversation_id
WHERE cc.id IS NULL;

SELECT 'orphan_ai_job_owner' AS check_id, COUNT(*) AS bad_count
FROM ai_job j
LEFT JOIN sys_user u ON u.id = j.owner_id
WHERE u.id IS NULL;

SELECT 'orphan_ai_job_course' AS check_id, COUNT(*) AS bad_count
FROM ai_job j
LEFT JOIN course c ON c.id = j.course_id
WHERE c.id IS NULL;

SELECT 'orphan_extraction_course' AS check_id, COUNT(*) AS bad_count
FROM extraction_review er
LEFT JOIN course c ON c.id = er.course_id
WHERE er.course_id IS NOT NULL
  AND c.id IS NULL;

SELECT 'orphan_diag_student' AS check_id, COUNT(*) AS bad_count
FROM diagnostic_session ds
LEFT JOIN sys_user u ON u.id = ds.student_id
WHERE u.id IS NULL;

SELECT 'orphan_diag_course' AS check_id, COUNT(*) AS bad_count
FROM diagnostic_session ds
LEFT JOIN course c ON c.id = ds.course_id
WHERE c.id IS NULL;

-- ── 5) 跨课程错配（question_node / mastery / path / practice）────────────
SELECT 'cross_course_qn_mismatch' AS check_id, COUNT(*) AS bad_count
FROM question_node qn
JOIN question q ON q.id = qn.question_id
JOIN kg_node n ON n.id = qn.node_id
WHERE q.course_id <> n.course_id;

SELECT 'cross_course_mastery_node' AS check_id, COUNT(*) AS bad_count
FROM student_mastery m
JOIN kg_node n ON n.id = m.node_id
WHERE m.course_id <> n.course_id;

SELECT 'cross_course_snapshot_node' AS check_id, COUNT(*) AS bad_count
FROM mastery_snapshot s
JOIN kg_node n ON n.id = s.node_id
WHERE s.course_id <> n.course_id;

SELECT 'cross_course_path_item_node' AS check_id, COUNT(*) AS bad_count
FROM learning_path_item i
JOIN learning_path lp ON lp.id = i.path_id
JOIN kg_node n ON n.id = i.node_id
WHERE lp.course_id <> n.course_id;

SELECT 'cross_course_path_target_node' AS check_id, COUNT(*) AS bad_count
FROM learning_path lp
JOIN kg_node n ON n.id = lp.target_node_id
WHERE lp.target_node_id IS NOT NULL
  AND lp.course_id <> n.course_id;

SELECT 'cross_course_practice_node' AS check_id, COUNT(*) AS bad_count
FROM practice_session ps
JOIN kg_node n ON n.id = ps.node_id
WHERE ps.course_id <> n.course_id;

SELECT 'practice_path_item_scope_mismatch' AS check_id, COUNT(*) AS bad_count
FROM practice_session ps
JOIN learning_path_item i ON i.id = ps.path_item_id
JOIN learning_path lp ON lp.id = i.path_id
WHERE ps.path_item_id IS NOT NULL
  AND (ps.student_id <> lp.student_id
    OR ps.course_id <> lp.course_id
    OR ps.node_id <> i.node_id);

SELECT 'cross_course_answer_question' AS check_id, COUNT(*) AS bad_count
FROM answer_record ar
JOIN question q ON q.id = ar.question_id
WHERE ar.course_id <> q.course_id;

SELECT 'answer_fixed_diag_scope_mismatch' AS check_id, COUNT(*) AS bad_count
FROM answer_record ar
JOIN diagnostic_session ds ON ds.id = ar.fixed_diagnostic_session_id
WHERE ar.fixed_diagnostic_session_id IS NOT NULL
  AND (ar.student_id <> ds.student_id OR ar.course_id <> ds.course_id);

SELECT 'edge_node_course_mismatch' AS check_id, COUNT(*) AS bad_count
FROM kg_edge e
JOIN kg_node f ON f.id = e.from_node_id
JOIN kg_node t ON t.id = e.to_node_id
WHERE e.course_id <> f.course_id
   OR e.course_id <> t.course_id
   OR f.course_id <> t.course_id;

SELECT 'sc_current_path_course_mismatch' AS check_id, COUNT(*) AS bad_count
FROM student_course sc
JOIN learning_path lp ON lp.id = sc.current_path_id
WHERE sc.current_path_id IS NOT NULL
  AND (sc.student_id <> lp.student_id OR sc.course_id <> lp.course_id);

-- ── 6) 重复作答（同会话同题多条）────────────────────────────────────────
SELECT 'dup_answer_practice_session_question' AS check_id, COUNT(*) AS bad_count
FROM (
    SELECT session_id, question_id, COUNT(*) AS c
    FROM answer_record
    WHERE session_id IS NOT NULL
    GROUP BY session_id, question_id
    HAVING c > 1
) t;

SELECT 'dup_answer_fixed_diag_session_question' AS check_id, COUNT(*) AS bad_count
FROM (
    SELECT fixed_diagnostic_session_id, question_id, COUNT(*) AS c
    FROM answer_record
    WHERE fixed_diagnostic_session_id IS NOT NULL
    GROUP BY fixed_diagnostic_session_id, question_id
    HAVING c > 1
) t;

-- ── 7) 多条有效学习路径（status=1）──────────────────────────────────────
SELECT 'multi_active_learning_paths' AS check_id, COUNT(*) AS bad_count
FROM (
    SELECT student_id, course_id, COUNT(*) AS c
    FROM learning_path
    WHERE status = 1
    GROUP BY student_id, course_id
    HAVING c > 1
) t;

-- ── 8) 重复 (course_id, stem_hash) 与 stem_hash NULL ─────────────────────
SELECT 'null_stem_hash' AS check_id, COUNT(*) AS bad_count
FROM question q
WHERE q.stem_hash IS NULL;

SELECT 'dup_course_stem_hash' AS check_id, COUNT(*) AS bad_count
FROM (
    SELECT course_id, stem_hash, COUNT(*) AS c
    FROM question
    WHERE stem_hash IS NOT NULL
    GROUP BY course_id, stem_hash
    HAVING c > 1
) t;

-- 说明：以上全部为只读审计。每个 bad_count 必须为 0；inventory_count / review_count
-- 可以非 0，但必须记录复核结论。阻断项按 quarantine-orphans.sql 或专项 DML
-- 修复后再跑 V9；禁止在未复核时强行 contract 迁移。
