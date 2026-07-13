-- V6: question.stem_hash — course-scoped unique stem fingerprint
-- CHAR(64) SHA-256 hex (MySQL SHA2(TRIM(stem), 256)); precheck, backfill, unique.
-- Duplicate normalized stems abort before the first persistent DDL.

DELIMITER $$

DROP PROCEDURE IF EXISTS `_v6_precheck`$$

CREATE PROCEDURE `_v6_precheck`()
BEGIN
    DECLARE v_cnt BIGINT DEFAULT 0;

    SELECT COUNT(*) INTO v_cnt FROM (
        SELECT `course_id`, SHA2(TRIM(`stem`), 256) AS `normalized_stem_hash`
        FROM `question`
        GROUP BY `course_id`, SHA2(TRIM(`stem`), 256)
        HAVING COUNT(*) > 1
    ) duplicate_stems;
    IF v_cnt > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V6 precheck: duplicate normalized question stems';
    END IF;
END$$

DELIMITER ;

CALL `_v6_precheck`();
DROP PROCEDURE IF EXISTS `_v6_precheck`;

-- ── 1. Add nullable column ───────────────────────────────────────────────────
ALTER TABLE `question`
    ADD COLUMN `stem_hash` CHAR(64) NULL
        COMMENT '题干 SHA-256 十六进制指纹（trim 后，与应用 StemHash 一致）' AFTER `stem`;

-- ── 2. Backfill ──────────────────────────────────────────────────────────────
UPDATE `question`
SET `stem_hash` = SHA2(TRIM(`stem`), 256)
WHERE `stem_hash` IS NULL;

-- ── 3. Unique key; V9 audits NULL and contracts the column NOT NULL ─────────
ALTER TABLE `question`
    ADD UNIQUE KEY `uk_question_course_stem_hash` (`course_id`, `stem_hash`);
