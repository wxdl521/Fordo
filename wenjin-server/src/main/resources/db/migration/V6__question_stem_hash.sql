-- V6: question.stem_hash — course-scoped unique stem fingerprint
-- CHAR(64) SHA-256 hex (MySQL SHA2(TRIM(stem), 256)); backfill then unique.
-- Duplicate groups are selected for ops visibility; temp UK insert aborts migrate if any remain.

-- ── 1. Add nullable column ───────────────────────────────────────────────────
ALTER TABLE `question`
    ADD COLUMN `stem_hash` CHAR(64) NULL
        COMMENT '题干 SHA-256 十六进制指纹（trim 后，与应用 StemHash 一致）' AFTER `stem`;

-- ── 2. Backfill ──────────────────────────────────────────────────────────────
UPDATE `question`
SET `stem_hash` = SHA2(TRIM(`stem`), 256)
WHERE `stem_hash` IS NULL;

-- ── 3. Report duplicate groups (course_id, stem_hash) ────────────────────────
SELECT q.`course_id`,
       q.`stem_hash`,
       COUNT(*) AS `dup_count`,
       GROUP_CONCAT(q.`id` ORDER BY q.`id`) AS `question_ids`
FROM `question` q
WHERE q.`stem_hash` IS NOT NULL
GROUP BY q.`course_id`, q.`stem_hash`
HAVING COUNT(*) > 1;

-- ── 4. Abort if any duplicates (INSERT into temp PK → ER_DUP_ENTRY) ──────────
CREATE TEMPORARY TABLE `_v6_stem_hash_uk` (
    `course_id` BIGINT   NOT NULL,
    `stem_hash` CHAR(64) NOT NULL,
    PRIMARY KEY (`course_id`, `stem_hash`)
);

INSERT INTO `_v6_stem_hash_uk` (`course_id`, `stem_hash`)
SELECT `course_id`, `stem_hash`
FROM `question`
WHERE `stem_hash` IS NOT NULL;

DROP TEMPORARY TABLE `_v6_stem_hash_uk`;

-- ── 5. NOT NULL + unique key ───────────────────────────────────────────────────
ALTER TABLE `question`
    MODIFY COLUMN `stem_hash` CHAR(64) NOT NULL
        COMMENT '题干 SHA-256 十六进制指纹（trim 后，与应用 StemHash 一致）',
    ADD UNIQUE KEY `uk_question_course_stem_hash` (`course_id`, `stem_hash`);
