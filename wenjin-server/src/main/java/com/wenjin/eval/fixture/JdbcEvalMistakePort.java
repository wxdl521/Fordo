package com.wenjin.eval.fixture;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;

/**
 * JDBC mistake seeding against the eval world: picks the node's first seeded single-choice question, tags its
 * first wrong option's {@code point_node_code} with the misconception node (idempotent UPDATE on the dedicated
 * eval question bank) and inserts a wrong {@code answer_record} (scene=2 practice, no session row).
 */
public final class JdbcEvalMistakePort implements EvalMistakePort {

    private final JdbcTemplate jdbc;

    public JdbcEvalMistakePort(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean recordMistake(long studentId, long courseId, long nodeId, String misconceptionNodeCode,
                                 int minutesAgo) {
        List<Long> questionIds = jdbc.queryForList(
                "SELECT q.id FROM question q JOIN question_node qn ON qn.question_id = q.id AND qn.weight = 1"
                        + " WHERE qn.node_id = ? AND q.course_id = ? AND q.type = 1 AND q.status = 1"
                        + " ORDER BY q.id LIMIT 1",
                Long.class, nodeId, courseId);
        if (questionIds.isEmpty()) {
            return false;
        }
        long questionId = questionIds.get(0);
        List<String> wrongKeys = jdbc.queryForList(
                "SELECT option_key FROM question_option WHERE question_id = ? AND is_correct = 0"
                        + " ORDER BY option_key LIMIT 1",
                String.class, questionId);
        if (wrongKeys.isEmpty()) {
            return false;
        }
        String key = wrongKeys.get(0);
        jdbc.update("UPDATE question_option SET point_node_code = ? WHERE question_id = ? AND option_key = ?",
                misconceptionNodeCode, questionId, key);
        jdbc.update("INSERT INTO answer_record (student_id, course_id, question_id, student_answer, is_correct,"
                        + " answered_at, scene) VALUES (?, ?, ?, ?, 0, NOW() - INTERVAL ? MINUTE, 2)",
                studentId, courseId, questionId, key, Math.max(0, minutesAgo));
        return true;
    }
}
