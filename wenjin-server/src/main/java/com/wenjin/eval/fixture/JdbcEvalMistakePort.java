package com.wenjin.eval.fixture;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JDBC mistake seeding against the eval world: picks the node's first seeded single-choice question, tags its
 * first wrong option's {@code point_node_code} with the misconception node (idempotent UPDATE on the dedicated
 * eval question bank) and inserts a wrong {@code answer_record} (scene=2 practice, no session row).
 *
 * <p>The tagged option's text is rewritten to actually embody the misconception (it states the misconception
 * node's content as if it were this node's), so a tutor can contrast the chosen option with the correct one.
 * A generic "与「X」无关的表述" distractor gave the model nothing to explain (LIVE S22 misconception_fit 2/5).
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
        String distractor = misconceptionDistractorText(courseId, nodeId, misconceptionNodeCode);
        if (distractor != null) {
            jdbc.update("UPDATE question_option SET point_node_code = ?, option_text = ?"
                            + " WHERE question_id = ? AND option_key = ?",
                    misconceptionNodeCode, distractor, questionId, key);
        } else {
            jdbc.update("UPDATE question_option SET point_node_code = ? WHERE question_id = ? AND option_key = ?",
                    misconceptionNodeCode, questionId, key);
        }
        jdbc.update("INSERT INTO answer_record (student_id, course_id, question_id, student_answer, is_correct,"
                        + " answered_at, scene) VALUES (?, ?, ?, ?, 0, NOW() - INTERVAL ? MINUTE, 2)",
                studentId, courseId, questionId, key, Math.max(0, minutesAgo));
        return true;
    }

    /** @return the misconception-embodying option text, or null when either node cannot be resolved */
    private String misconceptionDistractorText(long courseId, long nodeId, String misconceptionNodeCode) {
        List<String> names = jdbc.queryForList("SELECT name FROM kg_node WHERE id = ?", String.class, nodeId);
        List<Map<String, Object>> misconception = jdbc.queryForList(
                "SELECT name, description FROM kg_node WHERE course_id = ? AND node_code = ?",
                courseId, misconceptionNodeCode);
        if (names.isEmpty() || misconception.isEmpty()) {
            return null;
        }
        Map<String, Object> m = misconception.get(0);
        return distractorText(names.get(0), str(m.get("name")), str(m.get("description")));
    }

    /**
     * A plausible-but-wrong statement that attributes the misconception node's content to the question node,
     * e.g. {@code 「用例图」的核心内容就是访谈、问卷、观察等需求获取方法}; the option itself does not reveal that it
     * is wrong.
     */
    static String distractorText(String nodeName, String misconceptionName, String misconceptionDescription) {
        if (nodeName == null || nodeName.isBlank()) {
            return null;
        }
        String content = misconceptionDescription != null && !misconceptionDescription.isBlank()
                ? misconceptionDescription.trim()
                : misconceptionName;
        if (content == null || content.isBlank()) {
            return null;
        }
        return "「" + nodeName.trim() + "」的核心内容就是" + content.trim();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
