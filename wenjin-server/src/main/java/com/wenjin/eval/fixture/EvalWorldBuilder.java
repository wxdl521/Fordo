package com.wenjin.eval.fixture;

import com.wenjin.eval.fixture.EvalCourseCatalog.Edge;
import com.wenjin.eval.fixture.EvalCourseCatalog.Material;
import com.wenjin.eval.fixture.EvalCourseCatalog.Node;
import com.wenjin.support.StemHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Idempotently builds the eval world in a dedicated database: teacher + student, the
 * {@link EvalCourseCatalog} course with nodes, prerequisite edges, APPROVED questions and a small RAG
 * corpus. Re-running only inserts what is missing. Refuses to run against the demo database.
 */
public final class EvalWorldBuilder {

    private static final Logger log = LoggerFactory.getLogger(EvalWorldBuilder.class);

    /** Not a usable login; eval users never authenticate. */
    private static final String PASSWORD_PLACEHOLDER = "$2a$10$evalevalevalevalevalevaOeval.not.a.real.password.hash";

    private final JdbcTemplate jdbc;

    public EvalWorldBuilder(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public synchronized EvalWorld ensureWorld() {
        EvalDatabaseGuard.assertSafe(jdbc);
        long teacherId = ensureUser(EvalCourseCatalog.TEACHER_USERNAME, "评测教师", 1);
        long studentId = ensureUser(EvalCourseCatalog.STUDENT_USERNAME, "评测学生", 2);
        long courseId = ensureCourse(teacherId);
        jdbc.update("INSERT IGNORE INTO student_course (student_id, course_id) VALUES (?, ?)", studentId, courseId);

        Map<String, Long> nodeIds = new HashMap<>();
        for (Node node : EvalCourseCatalog.NODES) {
            nodeIds.put(node.code(), ensureNode(courseId, node));
        }
        for (Edge edge : EvalCourseCatalog.EDGES) {
            jdbc.update("INSERT IGNORE INTO kg_edge (course_id, from_node_id, to_node_id, relation_type)"
                            + " VALUES (?, ?, ?, 1)",
                    courseId, nodeIds.get(edge.from()), nodeIds.get(edge.to()));
        }
        for (Node node : EvalCourseCatalog.NODES) {
            ensureQuestions(courseId, nodeIds.get(node.code()), node);
        }
        ensureMaterials(courseId, teacherId);
        log.info("eval world ready: course={} student={} nodes={}", courseId, studentId, nodeIds.size());
        return new EvalWorld(teacherId, studentId, courseId, EvalCourseCatalog.COURSE_CODE,
                EvalCourseCatalog.nodeCodes());
    }

    private long ensureUser(String username, String realName, int role) {
        jdbc.update("INSERT IGNORE INTO sys_user (username, password, real_name, role, status)"
                + " VALUES (?, ?, ?, ?, 1)", username, PASSWORD_PLACEHOLDER, realName, role);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private long ensureCourse(long teacherId) {
        jdbc.update("INSERT IGNORE INTO course (code, name, description, teacher_id, status) VALUES (?, ?, ?, ?, 1)",
                EvalCourseCatalog.COURSE_CODE, EvalCourseCatalog.COURSE_NAME, "伴侣评测专用隔离课程", teacherId);
        return jdbc.queryForObject("SELECT id FROM course WHERE code = ?", Long.class, EvalCourseCatalog.COURSE_CODE);
    }

    private long ensureNode(long courseId, Node node) {
        jdbc.update("INSERT IGNORE INTO kg_node (course_id, node_code, name, chapter, difficulty, is_key, description)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                courseId, node.code(), node.name(), node.chapter(), node.difficulty(), node.key() ? 1 : 0,
                node.description());
        return jdbc.queryForObject("SELECT id FROM kg_node WHERE course_id = ? AND node_code = ?",
                Long.class, courseId, node.code());
    }

    private void ensureQuestions(long courseId, long nodeId, Node node) {
        for (int i = 1; i <= EvalCourseCatalog.QUESTIONS_PER_NODE; i++) {
            String stem = "【评测题】关于「" + node.name() + "」的第" + i + "题：下列说法正确的是？";
            String hash = StemHash.sha256Hex(stem);
            List<Long> existing = jdbc.queryForList(
                    "SELECT id FROM question WHERE course_id = ? AND stem_hash = ?", Long.class, courseId, hash);
            if (!existing.isEmpty()) {
                continue;
            }
            jdbc.update("INSERT INTO question (course_id, stem, stem_hash, type, difficulty, answer, analysis, source, status)"
                            + " VALUES (?, ?, ?, 1, ?, 'A', ?, 3, 1)",
                    courseId, stem, hash, node.difficulty(), "「" + node.name() + "」：" + node.description());
            long questionId = jdbc.queryForObject(
                    "SELECT id FROM question WHERE course_id = ? AND stem_hash = ?", Long.class, courseId, hash);
            jdbc.update("INSERT INTO question_option (question_id, option_key, option_text, is_correct) VALUES (?, 'A', ?, 1)",
                    questionId, node.description());
            jdbc.update("INSERT INTO question_option (question_id, option_key, option_text, is_correct) VALUES (?, 'B', ?, 0)",
                    questionId, "与「" + node.name() + "」无关的表述");
            jdbc.update("INSERT INTO question_option (question_id, option_key, option_text, is_correct) VALUES (?, 'C', ?, 0)",
                    questionId, "只适用于硬件设计的表述");
            jdbc.update("INSERT INTO question_node (question_id, node_id, weight) VALUES (?, ?, 1)", questionId, nodeId);
        }
    }

    private void ensureMaterials(long courseId, long teacherId) {
        for (Material m : EvalCourseCatalog.MATERIALS) {
            try {
                List<Long> sources = jdbc.queryForList(
                        "SELECT id FROM doc_source WHERE course_id = ? AND title = ?", Long.class, courseId, m.title());
                if (!sources.isEmpty()) {
                    continue;
                }
                jdbc.update("INSERT INTO doc_source (course_id, title, kind, status, created_by) VALUES (?, ?, 0, 1, ?)",
                        courseId, m.title(), teacherId);
                long sourceId = jdbc.queryForObject(
                        "SELECT id FROM doc_source WHERE course_id = ? AND title = ?", Long.class, courseId, m.title());
                jdbc.update("INSERT INTO doc_chunk (source_id, seq, content, char_count) VALUES (?, 0, ?, ?)",
                        sourceId, m.content(), m.content().length());
                long chunkId = jdbc.queryForObject(
                        "SELECT id FROM doc_chunk WHERE source_id = ? AND seq = 0", Long.class, sourceId);
                jdbc.update("INSERT INTO chunk_node_map (chunk_id, course_id, node_code, review_status) VALUES (?, ?, ?, 1)",
                        chunkId, courseId, m.nodeCode());
            } catch (RuntimeException e) {
                // RAG corpus is optional (e.g. ngram FULLTEXT unavailable); search_materials then returns empty.
                log.warn("eval material seed skipped ({}): {}", m.title(), e.getMessage());
            }
        }
    }
}
