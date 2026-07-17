-- V5: concurrency guards for mastery lost-update and multi-active learning paths
-- - student_course.current_path_id: single pointer to the current path
-- - learning_path.active_guard: at most one active path per student×course
-- - learning_path_item unique (path_id, step_order) / (path_id, node_id)
-- Duplicate path items are migration-blocking data debt: abort before the first
-- persistent DDL so no source rows are deleted or partially transformed.

DELIMITER $$

DROP PROCEDURE IF EXISTS `_v5_precheck`$$

CREATE PROCEDURE `_v5_precheck`()
BEGIN
    DECLARE v_cnt BIGINT DEFAULT 0;

    SELECT COUNT(*) INTO v_cnt FROM (
        SELECT `path_id`, `step_order`
        FROM `learning_path_item`
        GROUP BY `path_id`, `step_order`
        HAVING COUNT(*) > 1
    ) duplicate_steps;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V5 precheck: duplicate learning_path_item(path_id, step_order)';
    END IF;

    SELECT COUNT(*) INTO v_cnt FROM (
        SELECT `path_id`, `node_id`
        FROM `learning_path_item`
        GROUP BY `path_id`, `node_id`
        HAVING COUNT(*) > 1
    ) duplicate_nodes;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V5 precheck: duplicate learning_path_item(path_id, node_id)';
    END IF;
END$$

DELIMITER ;

CALL `_v5_precheck`();
DROP PROCEDURE IF EXISTS `_v5_precheck`;

-- ── 1. student_course.current_path_id ────────────────────────────────────────
ALTER TABLE `student_course`
    ADD COLUMN `current_path_id` BIGINT NULL
        COMMENT '当前有效学习路径（物理外键→learning_path.id）' AFTER `course_id`;

-- ── 2. Deduplicate multi-active learning_path rows (keep max id) ─────────────
UPDATE `learning_path` lp
    INNER JOIN (
        SELECT `student_id`, `course_id`, MAX(`id`) AS `keep_id`
        FROM `learning_path`
        WHERE `status` = 1
        GROUP BY `student_id`, `course_id`
        HAVING COUNT(*) > 1
    ) d ON lp.`student_id` = d.`student_id`
       AND lp.`course_id` = d.`course_id`
       AND lp.`status` = 1
       AND lp.`id` <> d.`keep_id`
SET lp.`status` = 0;

-- Backfill pointer from remaining active paths
UPDATE `student_course` sc
    INNER JOIN (
        SELECT `student_id`, `course_id`, MAX(`id`) AS `path_id`
        FROM `learning_path`
        WHERE `status` = 1
        GROUP BY `student_id`, `course_id`
    ) a ON sc.`student_id` = a.`student_id` AND sc.`course_id` = a.`course_id`
SET sc.`current_path_id` = a.`path_id`;

-- ── 3. learning_path.active_guard + unique (one active per student×course) ───
ALTER TABLE `learning_path`
    ADD COLUMN `active_guard` TINYINT
        GENERATED ALWAYS AS (IF(`status` = 1, 1, NULL)) STORED
        COMMENT '生成列：status=1 时为 1，否则 NULL；配合唯一约束保证至多一条有效路径',
    ADD UNIQUE KEY `uk_learning_path_active` (`student_id`, `course_id`, `active_guard`);

-- FK after backfill so existing pointers are valid (NULL allowed)
ALTER TABLE `student_course`
    ADD CONSTRAINT `fk_sc_current_path`
        FOREIGN KEY (`current_path_id`) REFERENCES `learning_path` (`id`);

-- ── 4. learning_path_item unique keys ───────────────────────────────────────
ALTER TABLE `learning_path_item`
    ADD UNIQUE KEY `uk_path_step_order` (`path_id`, `step_order`),
    ADD UNIQUE KEY `uk_path_node` (`path_id`, `node_id`);
