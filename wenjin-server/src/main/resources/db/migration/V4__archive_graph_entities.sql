-- V4: soft-archive columns for course / kg_node / kg_edge
-- Diff-import and delete-as-archive rely on archived_at IS NULL for "active" rows.
-- No physical FKs; no course.teacher_id NOT NULL (Task 19 V9).

ALTER TABLE `course`
    ADD COLUMN `archived_at` DATETIME NULL COMMENT '软归档时间；NULL=生效' AFTER `updated_at`;

ALTER TABLE `kg_node`
    ADD COLUMN `archived_at` DATETIME NULL COMMENT '软归档时间；NULL=生效' AFTER `updated_at`;

ALTER TABLE `kg_edge`
    ADD COLUMN `archived_at` DATETIME NULL COMMENT '软归档时间；NULL=生效' AFTER `updated_at`;

CREATE INDEX `idx_course_owner_active` ON `course` (`teacher_id`, `archived_at`, `id`);
CREATE INDEX `idx_node_course_active` ON `kg_node` (`course_id`, `archived_at`, `id`);
CREATE INDEX `idx_edge_course_active` ON `kg_edge` (`course_id`, `archived_at`, `id`);
