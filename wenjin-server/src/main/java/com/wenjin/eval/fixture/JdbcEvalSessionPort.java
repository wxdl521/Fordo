package com.wenjin.eval.fixture;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/**
 * Clears student-scoped mutable sessions for eval reset (conversations, practice, agent runs).
 */
public final class JdbcEvalSessionPort implements EvalSessionPort {

    private final JdbcTemplate jdbc;

    public JdbcEvalSessionPort(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void clearSessions(long studentId, long courseId) {
        // companion messages for student's conversations
        jdbc.update("""
                DELETE cm FROM companion_message cm
                INNER JOIN companion_conversation cc ON cc.id = cm.conversation_id
                WHERE cc.student_id = ? AND cc.course_id = ?
                """, studentId, courseId);
        jdbc.update("""
                DELETE FROM companion_conversation WHERE student_id = ? AND course_id = ?
                """, studentId, courseId);
        jdbc.update("""
                DELETE FROM practice_session WHERE student_id = ? AND course_id = ?
                """, studentId, courseId);
        // agent steps for student's runs
        jdbc.update("""
                DELETE s FROM agent_step s
                INNER JOIN agent_run r ON r.id = s.run_id
                WHERE r.student_id = ? AND r.course_id = ?
                """, studentId, courseId);
        jdbc.update("""
                DELETE FROM agent_run WHERE student_id = ? AND course_id = ?
                """, studentId, courseId);
        jdbc.update("""
                DELETE FROM answer_record WHERE student_id = ? AND course_id = ?
                """, studentId, courseId);
    }
}
