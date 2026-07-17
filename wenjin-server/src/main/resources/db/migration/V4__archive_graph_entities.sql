-- V4: soft-archive columns for course / kg_node / kg_edge
-- Diff-import and delete-as-archive rely on archived_at IS NULL for "active" rows.
-- No physical FKs; no course.teacher_id NOT NULL (Task 19 V9).

ALTER TABLE `course`
    ADD COLUMN `archived_at` DATETIME NULL COMMENT '软归档时间；NULL=生效' AFTER `updated_at`;

ALTER TABLE `kg_node`
    ADD COLUMN `archived_at` DATETIME NULL COMMENT '软归档时间；NULL=生效' AFTER `updated_at`,
    ADD COLUMN `archived_by_course` TINYINT NOT NULL DEFAULT 0
        COMMENT '1=由课程归档级联，恢复课程时可恢复；0=独立归档' AFTER `archived_at`,
    ADD CONSTRAINT `chk_node_course_archive_marker`
        CHECK (`archived_by_course` IN (0, 1)
            AND (`archived_by_course` = 0 OR `archived_at` IS NOT NULL));

ALTER TABLE `kg_edge`
    ADD COLUMN `archived_at` DATETIME NULL COMMENT '软归档时间；NULL=生效' AFTER `updated_at`,
    ADD COLUMN `archived_by_course` TINYINT NOT NULL DEFAULT 0
        COMMENT '1=由课程归档级联，恢复课程时可恢复；0=独立归档' AFTER `archived_at`,
    ADD CONSTRAINT `chk_edge_course_archive_marker`
        CHECK (`archived_by_course` IN (0, 1)
            AND (`archived_by_course` = 0 OR `archived_at` IS NOT NULL));

CREATE INDEX `idx_course_owner_active` ON `course` (`teacher_id`, `archived_at`, `id`);
CREATE INDEX `idx_node_course_active` ON `kg_node` (`course_id`, `archived_at`, `id`);
CREATE INDEX `idx_edge_course_active` ON `kg_edge` (`course_id`, `archived_at`, `id`);
