package com.wenjin.eval.fixture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * JDBC {@link EvalRosterPort} against the dedicated eval database. Roster users are
 * {@code eval_roster_1..5}; every write is plain SQL with explicit timestamps (relative to NOW()) so the
 * teacher analytics see deterministic data. Resets run on one connection with FK checks off.
 */
public final class JdbcEvalRosterPort implements EvalRosterPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcEvalRosterPort.class);

    static final String USERNAME_PREFIX = "eval_roster_";
    static final String OTHER_COURSE_CODE = "EVAL_OTHER";
    static final String OTHER_COURSE_NAME = "另一门课程（评测）";
    private static final String PASSWORD_PLACEHOLDER = "$2a$10$evalevalevalevalevalevaOeval.not.a.real.password.hash";

    /** Student-scoped tables cleared per roster user; the user-id list is inlined (ids read from the DB). */
    static final List<String> ROSTER_DELETES = List.of(
            "DELETE cm FROM companion_message cm INNER JOIN companion_conversation cc ON cc.id = cm.conversation_id"
                    + " WHERE cc.student_id IN (%s)",
            "DELETE FROM companion_conversation WHERE student_id IN (%s)",
            "DELETE FROM answer_record WHERE student_id IN (%s)",
            "DELETE FROM practice_session WHERE student_id IN (%s)",
            "DELETE FROM student_mastery WHERE student_id IN (%s)",
            "DELETE FROM mastery_snapshot WHERE student_id IN (%s)",
            "DELETE FROM student_course WHERE student_id IN (%s)");

    /** Teacher-side tables; each takes (teacherId, courseId). */
    static final List<String> TEACHER_DELETES = List.of(
            "DELETE s FROM agent_step s INNER JOIN agent_run r ON r.id = s.run_id"
                    + " WHERE r.student_id = ? AND r.course_id = ? AND r.kind = 'TEACHER_CHAT'",
            "DELETE FROM agent_run WHERE student_id = ? AND course_id = ? AND kind = 'TEACHER_CHAT'",
            "DELETE tm FROM teacher_message tm INNER JOIN teacher_conversation tc ON tc.id = tm.conversation_id"
                    + " WHERE tc.teacher_id = ? AND tc.course_id = ?",
            "DELETE FROM teacher_conversation WHERE teacher_id = ? AND course_id = ?");

    private final JdbcTemplate jdbc;

    public JdbcEvalRosterPort(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void clearRoster() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM sys_user WHERE username LIKE ?", Long.class,
                USERNAME_PREFIX.replace("_", "\\_") + "%");
        if (ids.isEmpty()) {
            return;
        }
        String inList = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        runDeletes(ROSTER_DELETES.stream().map(sql -> sql.formatted(inList)).toList(), null);
    }

    @Override
    public void setPrimaryStudentEnrolled(long studentId, long courseId, boolean enrolled) {
        if (enrolled) {
            enroll(studentId, courseId);
        } else {
            jdbc.update("DELETE FROM student_course WHERE student_id = ? AND course_id = ?", studentId, courseId);
        }
    }

    @Override
    public long ensureRosterUser(int slot, String realName) {
        if (slot < 1 || slot > MAX_SLOTS) {
            throw new IllegalArgumentException("roster slot out of range: " + slot);
        }
        String username = USERNAME_PREFIX + slot;
        jdbc.update("INSERT IGNORE INTO sys_user (username, password, real_name, role, status) VALUES (?, ?, ?, 2, 1)",
                username, PASSWORD_PLACEHOLDER, realName);
        jdbc.update("UPDATE sys_user SET real_name = ? WHERE username = ?", realName, username);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    @Override
    public void enroll(long studentId, long courseId) {
        jdbc.update("INSERT IGNORE INTO student_course (student_id, course_id) VALUES (?, ?)", studentId, courseId);
    }

    @Override
    public long enrollInOtherCourse(long studentId, long teacherId) {
        jdbc.update("INSERT IGNORE INTO course (code, name, description, teacher_id, status) VALUES (?, ?, ?, ?, 1)",
                OTHER_COURSE_CODE, OTHER_COURSE_NAME, "伴侣评测专用：用于验证教师助教不越出本课程", teacherId);
        long courseId = jdbc.queryForObject("SELECT id FROM course WHERE code = ?", Long.class, OTHER_COURSE_CODE);
        enroll(studentId, courseId);
        return courseId;
    }

    @Override
    public void writeMastery(long studentId, long courseId, long nodeId, double raw01) {
        double score = Math.round(Math.max(0, Math.min(1, raw01)) * 10000) / 100.0;
        jdbc.update("INSERT INTO student_mastery (student_id, course_id, node_id, mastery_score, mastery_level, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, NOW()) ON DUPLICATE KEY UPDATE mastery_score = VALUES(mastery_score),"
                        + " mastery_level = VALUES(mastery_level), updated_at = NOW()",
                studentId, courseId, nodeId, score, levelOf(score));
    }

    @Override
    public void writeSnapshot(long studentId, long courseId, long nodeId, double score01, int daysAgo) {
        double score = Math.round(Math.max(0, Math.min(1, score01)) * 10000) / 100.0;
        jdbc.update("INSERT INTO mastery_snapshot (student_id, course_id, node_id, mastery_score, mastery_level, snapshot_at)"
                        + " VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL ? DAY)",
                studentId, courseId, nodeId, score, levelOf(score), Math.max(0, daysAgo));
    }

    @Override
    public boolean recordAnswer(long studentId, long courseId, long nodeId, boolean correct, int minutesAgo) {
        List<Long> questionIds = jdbc.queryForList(
                "SELECT q.id FROM question q JOIN question_node qn ON qn.question_id = q.id AND qn.weight = 1"
                        + " WHERE qn.node_id = ? AND q.course_id = ? AND q.type = 1 AND q.status = 1"
                        + " ORDER BY q.id LIMIT 1",
                Long.class, nodeId, courseId);
        if (questionIds.isEmpty()) {
            return false;
        }
        long questionId = questionIds.get(0);
        List<String> keys = jdbc.queryForList(
                "SELECT option_key FROM question_option WHERE question_id = ? AND is_correct = ?"
                        + " ORDER BY option_key LIMIT 1",
                String.class, questionId, correct ? 1 : 0);
        if (keys.isEmpty()) {
            return false;
        }
        jdbc.update("INSERT INTO answer_record (student_id, course_id, question_id, student_answer, is_correct,"
                        + " answered_at, scene) VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL ? MINUTE, 2)",
                studentId, courseId, questionId, keys.get(0), correct ? 1 : 0, Math.max(0, minutesAgo));
        return true;
    }

    @Override
    public void recordPrivateChat(long studentId, long courseId, String text) {
        jdbc.update("INSERT INTO companion_conversation (student_id, course_id, title) VALUES (?, ?, ?)",
                studentId, courseId, "评测私密对话");
        long conversationId = jdbc.queryForObject(
                "SELECT MAX(id) FROM companion_conversation WHERE student_id = ? AND course_id = ?",
                Long.class, studentId, courseId);
        jdbc.update("INSERT INTO companion_message (conversation_id, role, content) VALUES (?, 1, ?)",
                conversationId, text);
    }

    @Override
    public void clearTeacherState(long teacherId, long courseId) {
        runDeletes(TEACHER_DELETES, new long[]{teacherId, courseId});
    }

    private void runDeletes(List<String> statements, long[] params) {
        jdbc.execute((Connection con) -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
            }
            try {
                for (String sql : statements) {
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        if (params != null) {
                            for (int i = 0; i < params.length; i++) {
                                ps.setLong(i + 1, params[i]);
                            }
                        }
                        ps.executeUpdate();
                    } catch (java.sql.SQLException e) {
                        log.debug("eval roster reset skipped: {} ({})", sql, e.getMessage());
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

    /** Same cut-offs as the production mastery levels (75 mastered / 40 weak). */
    private static int levelOf(double score100) {
        return score100 >= 75 ? 2 : score100 >= 40 ? 1 : 0;
    }
}
