-- V5: concurrency guards for mastery lost-update and multi-active learning paths
-- - student_course.current_path_id: single pointer to the current path
-- - learning_path.active_guard: at most one active path per student×course
-- - learning_path_item unique (path_id, step_order) / (path_id, node_id)
-- Dedupe before adding unique constraints. One-shot Flyway (no DELIMITER).

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

-- ── 4. learning_path_item unique keys (dedupe first, keep max id) ────────────
DELETE i FROM `learning_path_item` i
    INNER JOIN `learning_path_item` j
        ON i.`path_id` = j.`path_id`
       AND i.`step_order` = j.`step_order`
       AND i.`id` < j.`id`;

DELETE i FROM `learning_path_item` i
    INNER JOIN `learning_path_item` j
        ON i.`path_id` = j.`path_id`
       AND i.`node_id` = j.`node_id`
       AND i.`id` < j.`id`;

ALTER TABLE `learning_path_item`
    ADD UNIQUE KEY `uk_path_step_order` (`path_id`, `step_order`),
    ADD UNIQUE KEY `uk_path_node` (`path_id`, `node_id`);
