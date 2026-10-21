package com.wenjin.eval.fixture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * Clears student-scoped mutable state for eval reset: conversations, practice, answers, agent trace, memory,
 * plans and learning paths. All deletes run on one connection with FK checks off (session scope) so the
 * order of dependent rows does not matter; tables missing on older schemas are skipped.
 */
public final class JdbcEvalSessionPort implements EvalSessionPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcEvalSessionPort.class);

    /** Each statement takes (studentId, courseId). */
    static final List<String> DELETES = List.of(
            "DELETE cm FROM companion_message cm INNER JOIN companion_conversation cc ON cc.id = cm.conversation_id"
                    + " WHERE cc.student_id = ? AND cc.course_id = ?",
            "DELETE FROM companion_conversation WHERE student_id = ? AND course_id = ?",
            "DELETE FROM answer_record WHERE student_id = ? AND course_id = ?",
            "DELETE FROM practice_session WHERE student_id = ? AND course_id = ?",
            "DELETE s FROM agent_step s INNER JOIN agent_run r ON r.id = s.run_id"
                    + " WHERE r.student_id = ? AND r.course_id = ?",
            "DELETE FROM agent_run WHERE student_id = ? AND course_id = ?",
            "DELETE FROM student_memory WHERE student_id = ? AND course_id = ?",
            "DELETE t FROM agent_plan_task t INNER JOIN agent_plan p ON p.id = t.plan_id"
                    + " WHERE p.student_id = ? AND p.course_id = ?",
            "DELETE FROM agent_plan WHERE student_id = ? AND course_id = ?",
            "DELETE i FROM learning_path_item i INNER JOIN learning_path p ON p.id = i.path_id"
                    + " WHERE p.student_id = ? AND p.course_id = ?",
            "DELETE FROM learning_path WHERE student_id = ? AND course_id = ?");

    private final JdbcTemplate jdbc;

    public JdbcEvalSessionPort(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void clearSessions(long studentId, long courseId) {
        jdbc.execute((Connection con) -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
            }
            try {
                for (String sql : DELETES) {
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        ps.setLong(1, studentId);
                        ps.setLong(2, courseId);
                        ps.executeUpdate();
                    } catch (java.sql.SQLException e) {
                        log.debug("eval reset skipped: {} ({})", sql, e.getMessage());
                    }
                }
            } finally {
                try (Statement st = con.createStatement()) {
                    st.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
            }
            return null;
        });
    }
}
