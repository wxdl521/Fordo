-- V9: relational integrity contract (physical FKs + NOT NULL + role CHECK)
--
-- ⚠ DEPLOY ONLY AFTER expand (V3–V8) + security-role-audit.sql / preflight all zero.
-- This is a *contract* migration: it hard-fails (SIGNAL 45000) if residual orphans,
-- cross-course mismatches, invalid roles, NULL teacher_id / stem_hash, or stem_hash
-- duplicates remain. Do not ship V9 in the same release window as soft expand
-- migrations unless audits are green on a production-like snapshot.
--
-- Task 7 named FK matrix (logical → physical):
--   course.teacher_id → sys_user.id
--   kg_node/kg_edge/question/*.course_id → course.id
--   kg_edge from/to → kg_node.id
--   question_option/question_node → question / kg_node
--   student_mastery / mastery_snapshot / answer_record / learning_path* /
--   practice_session / adaptive_diag_session / student_course / companion* /
--   ai_job / diagnostic_session → parents
-- Existing: fk_dsq_session, fk_dsq_question_course (V3), fk_sc_current_path (V5)
--
-- One-shot Flyway: temporary procedure for SIGNAL prechecks, then plain ALTERs.

-- ════════════════════════════════════════════════════════════════════════
-- 0) Pre-checks: abort with SQLSTATE 45000 if any residual integrity debt
-- ════════════════════════════════════════════════════════════════════════

DELIMITER $$

DROP PROCEDURE IF EXISTS `_v9_precheck`$$

CREATE PROCEDURE `_v9_precheck`()
BEGIN
    DECLARE v_cnt BIGINT DEFAULT 0;

    -- role unknown
    SELECT COUNT(*) INTO v_cnt FROM sys_user
    WHERE role IS NULL OR role NOT IN (1, 2, 3);
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: unknown sys_user.role (need IN 1,2,3)';
    END IF;

    -- course.teacher_id NULL / dangling / non TEACHER|ADMIN
    SELECT COUNT(*) INTO v_cnt FROM course WHERE teacher_id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: course.teacher_id IS NULL';
    END IF;

    SELECT COUNT(*) INTO v_cnt
    FROM course c
    LEFT JOIN sys_user u ON u.id = c.teacher_id
    WHERE c.teacher_id IS NOT NULL AND u.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: course.teacher_id dangling user';
    END IF;

    SELECT COUNT(*) INTO v_cnt
    FROM course c
    JOIN sys_user u ON u.id = c.teacher_id
    WHERE u.role NOT IN (1, 3) AND c.archived_at IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: course owner not TEACHER/ADMIN';
    END IF;

    -- stem_hash NULL / duplicates
    SELECT COUNT(*) INTO v_cnt FROM question WHERE stem_hash IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: question.stem_hash IS NULL';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM (
        SELECT course_id, stem_hash FROM question
        WHERE stem_hash IS NOT NULL
        GROUP BY course_id, stem_hash HAVING COUNT(*) > 1
    ) d;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: duplicate (course_id, stem_hash)';
    END IF;

    -- logical orphans (core)
    SELECT COUNT(*) INTO v_cnt FROM kg_node n
    LEFT JOIN course c ON c.id = n.course_id WHERE c.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan kg_node.course_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM kg_edge e
    LEFT JOIN course c ON c.id = e.course_id WHERE c.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan kg_edge.course_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM kg_edge e
    LEFT JOIN kg_node n ON n.id = e.from_node_id WHERE n.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan kg_edge.from_node_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM kg_edge e
    LEFT JOIN kg_node n ON n.id = e.to_node_id WHERE n.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan kg_edge.to_node_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM question q
    LEFT JOIN course c ON c.id = q.course_id WHERE c.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan question.course_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM question_option o
    LEFT JOIN question q ON q.id = o.question_id WHERE q.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan question_option.question_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM question_node qn
    LEFT JOIN question q ON q.id = qn.question_id WHERE q.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan question_node.question_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM question_node qn
    LEFT JOIN kg_node n ON n.id = qn.node_id WHERE n.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan question_node.node_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM student_mastery m
    LEFT JOIN sys_user u ON u.id = m.student_id WHERE u.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan student_mastery.student_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM student_mastery m
    LEFT JOIN course c ON c.id = m.course_id WHERE c.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan student_mastery.course_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM student_mastery m
    LEFT JOIN kg_node n ON n.id = m.node_id WHERE n.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan student_mastery.node_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM learning_path_item i
    LEFT JOIN learning_path lp ON lp.id = i.path_id WHERE lp.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan learning_path_item.path_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM learning_path_item i
    LEFT JOIN kg_node n ON n.id = i.node_id WHERE n.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan learning_path_item.node_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM student_course sc
    LEFT JOIN sys_user u ON u.id = sc.student_id WHERE u.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan student_course.student_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM student_course sc
    LEFT JOIN course c ON c.id = sc.course_id WHERE c.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan student_course.course_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM student_course sc
    LEFT JOIN learning_path lp ON lp.id = sc.current_path_id
    WHERE sc.current_path_id IS NOT NULL AND lp.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan student_course.current_path_id';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM companion_message cm
    LEFT JOIN companion_conversation cc ON cc.id = cm.conversation_id
    WHERE cc.id IS NULL;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: orphan companion_message.conversation_id';
    END IF;

    -- cross-course mismatches
    SELECT COUNT(*) INTO v_cnt FROM question_node qn
    JOIN question q ON q.id = qn.question_id
    JOIN kg_node n ON n.id = qn.node_id
    WHERE q.course_id <> n.course_id;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: cross-course question_node mismatch';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM student_mastery m
    JOIN kg_node n ON n.id = m.node_id
    WHERE m.course_id <> n.course_id;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: cross-course mastery.node mismatch';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM learning_path_item i
    JOIN learning_path lp ON lp.id = i.path_id
    JOIN kg_node n ON n.id = i.node_id
    WHERE lp.course_id <> n.course_id;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: cross-course path_item.node mismatch';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM practice_session ps
    JOIN kg_node n ON n.id = ps.node_id
    WHERE ps.course_id <> n.course_id;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: cross-course practice.node mismatch';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM kg_edge e
    JOIN kg_node f ON f.id = e.from_node_id
    JOIN kg_node t ON t.id = e.to_node_id
    WHERE e.course_id <> f.course_id
       OR e.course_id <> t.course_id
       OR f.course_id <> t.course_id;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: edge/node course mismatch';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM answer_record ar
    JOIN question q ON q.id = ar.question_id
    WHERE ar.course_id <> q.course_id;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V9 precheck: cross-course answer.question mismatch';
    END IF;
END$$

DELIMITER ;

CALL `_v9_precheck`();
DROP PROCEDURE IF EXISTS `_v9_precheck`;

-- ════════════════════════════════════════════════════════════════════════
-- 1) Column contracts: teacher_id NOT NULL, stem_hash NOT NULL, role CHECK
-- ════════════════════════════════════════════════════════════════════════

-- No auto-backfill of teacher_id: NULL rows must be fixed by ops before V9.
ALTER TABLE `course`
    MODIFY COLUMN `teacher_id` BIGINT NOT NULL
        COMMENT '创建教师（物理外键→sys_user.id）【V9】';

-- stem_hash already NOT NULL after V6; re-assert contract for snapshot parity
ALTER TABLE `question`
    MODIFY COLUMN `stem_hash` CHAR(64) NOT NULL
        COMMENT '题干 SHA-256 十六进制指纹（trim 后，与应用 StemHash 一致）【V9】';

ALTER TABLE `sys_user`
    ADD CONSTRAINT `chk_sys_user_role`
        CHECK (`role` IN (1, 2, 3));

-- ════════════════════════════════════════════════════════════════════════
-- 2) Named physical FKs (Task 7 matrix); RESTRICT default on parent delete
-- ════════════════════════════════════════════════════════════════════════

ALTER TABLE `course`
    ADD CONSTRAINT `fk_course_teacher`
        FOREIGN KEY (`teacher_id`) REFERENCES `sys_user` (`id`);

ALTER TABLE `kg_node`
    ADD CONSTRAINT `fk_node_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`id`);

ALTER TABLE `kg_edge`
    ADD CONSTRAINT `fk_edge_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
    ADD CONSTRAINT `fk_edge_from_node`
        FOREIGN KEY (`from_node_id`) REFERENCES `kg_node` (`id`),
    ADD CONSTRAINT `fk_edge_to_node`
        FOREIGN KEY (`to_node_id`) REFERENCES `kg_node` (`id`);

ALTER TABLE `question`
    ADD CONSTRAINT `fk_question_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`id`);

ALTER TABLE `question_option`
    ADD CONSTRAINT `fk_option_question`
        FOREIGN KEY (`question_id`) REFERENCES `question` (`id`);

ALTER TABLE `question_node`
    ADD CONSTRAINT `fk_qn_question`
        FOREIGN KEY (`question_id`) REFERENCES `question` (`id`),
    ADD CONSTRAINT `fk_qn_node`
        FOREIGN KEY (`node_id`) REFERENCES `kg_node` (`id`);

ALTER TABLE `student_mastery`
    ADD CONSTRAINT `fk_mastery_student`
        FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    ADD CONSTRAINT `fk_mastery_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
    ADD CONSTRAINT `fk_mastery_node`
        FOREIGN KEY (`node_id`) REFERENCES `kg_node` (`id`);

ALTER TABLE `mastery_snapshot`
    ADD CONSTRAINT `fk_snapshot_student`
        FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    ADD CONSTRAINT `fk_snapshot_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
    ADD CONSTRAINT `fk_snapshot_node`
        FOREIGN KEY (`node_id`) REFERENCES `kg_node` (`id`);

ALTER TABLE `learning_path`
    ADD CONSTRAINT `fk_path_student`
        FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    ADD CONSTRAINT `fk_path_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
    ADD CONSTRAINT `fk_path_target_node`
        FOREIGN KEY (`target_node_id`) REFERENCES `kg_node` (`id`);

ALTER TABLE `learning_path_item`
    ADD CONSTRAINT `fk_path_item_path`
        FOREIGN KEY (`path_id`) REFERENCES `learning_path` (`id`),
    ADD CONSTRAINT `fk_path_item_node`
        FOREIGN KEY (`node_id`) REFERENCES `kg_node` (`id`);

ALTER TABLE `practice_session`
    ADD CONSTRAINT `fk_practice_student`
        FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    ADD CONSTRAINT `fk_practice_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
    ADD CONSTRAINT `fk_practice_node`
        FOREIGN KEY (`node_id`) REFERENCES `kg_node` (`id`),
    ADD CONSTRAINT `fk_practice_path_item`
        FOREIGN KEY (`path_item_id`) REFERENCES `learning_path_item` (`id`);

ALTER TABLE `adaptive_diag_session`
    ADD CONSTRAINT `fk_adaptive_student`
        FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    ADD CONSTRAINT `fk_adaptive_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`id`);

ALTER TABLE `answer_record`
    -- session_id is interpreted by scene for backward compatibility and may
    -- identify different session tables; it cannot have one physical FK.
    ADD CONSTRAINT `fk_answer_student`
        FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    ADD CONSTRAINT `fk_answer_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
    ADD CONSTRAINT `fk_answer_question`
        FOREIGN KEY (`question_id`) REFERENCES `question` (`id`),
    ADD CONSTRAINT `fk_answer_fixed_diag`
        FOREIGN KEY (`fixed_diagnostic_session_id`) REFERENCES `diagnostic_session` (`id`);

ALTER TABLE `node_resource`
    ADD CONSTRAINT `fk_node_resource_node`
        FOREIGN KEY (`node_id`) REFERENCES `kg_node` (`id`),
    ADD CONSTRAINT `fk_node_resource_resource`
        FOREIGN KEY (`resource_id`) REFERENCES `resource` (`id`);

ALTER TABLE `student_course`
    ADD CONSTRAINT `fk_sc_student`
        FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    ADD CONSTRAINT `fk_sc_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`id`);
-- fk_sc_current_path already added in V5

ALTER TABLE `companion_conversation`
    ADD CONSTRAINT `fk_companion_student`
        FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    ADD CONSTRAINT `fk_companion_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`id`);

ALTER TABLE `companion_message`
    ADD CONSTRAINT `fk_companion_msg_conv`
        FOREIGN KEY (`conversation_id`) REFERENCES `companion_conversation` (`id`);

ALTER TABLE `extraction_review`
    ADD CONSTRAINT `fk_extraction_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`id`);

ALTER TABLE `ai_job`
    ADD CONSTRAINT `fk_ai_job_owner`
        FOREIGN KEY (`owner_id`) REFERENCES `sys_user` (`id`),
    ADD CONSTRAINT `fk_ai_job_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`id`);

ALTER TABLE `diagnostic_session`
    ADD CONSTRAINT `fk_diag_student`
        FOREIGN KEY (`student_id`) REFERENCES `sys_user` (`id`),
    ADD CONSTRAINT `fk_diag_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`id`);
-- fk_dsq_session / fk_dsq_question_course already added in V3
