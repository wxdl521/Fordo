package com.wenjin.eval.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.service.multiagent.TeacherWeeklyReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M11 teacher weekly report end-to-end on MySQL: seeds a throw-away course (active students, a disabled student,
 * an archived node, drop / idle / never-practiced students, companion activity) and generates the report through the
 * real provider + aggregations + {@code agent_report}. Cleans up in {@code finally}. Gated by {@code WENJIN_EVAL_IT=true}.
 */
@EnabledIfEnvironmentVariable(named = "WENJIN_EVAL_IT", matches = "true")
class TeacherWeeklyReportIT extends EvalDbSupport {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TeacherWeeklyReportService reportService;
    @Autowired private ObjectMapper om;

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private long user(String tag, String name, int role) {
        return insert("INSERT INTO sys_user (username, password, real_name, role, status) VALUES (?,?,?,?,1)",
                tag, "x", name, role);
    }

    private long questionId;

    private void answer(long student, long course, int daysAgo) {
        jdbc.update("INSERT INTO answer_record (student_id, course_id, question_id, student_answer, is_correct, answered_at, scene)"
                + " VALUES (?,?,?,'A',1,?,2)", student, course, questionId, LocalDateTime.now().minusDays(daysAgo).minusHours(1));
    }

    @Test
    @DisplayName("weekly report payload is populated from real data; idempotent; PRIVATE content never leaks")
    void weeklyReportEndToEnd() throws Exception {
        String tag = "wr" + (System.nanoTime() % 100000000L);
        Long course = null;
        List<Long> users = new ArrayList<>();
        try {
            long teacher = user(tag + "_t", "老师", 1);
            long s1 = user(tag + "_s1", "甲同学", 2);   // N1 dropped hard, active
            long s2 = user(tag + "_s2", "乙同学", 2);   // weak but steady, active
            long s3 = user(tag + "_s3", "丙同学", 2);   // never practiced
            long s4 = user(tag + "_s4", "丁同学", 2);   // last practice 20 days ago
            long disabled = user(tag + "_d", "禁用生", 2);
            jdbc.update("UPDATE sys_user SET status=0 WHERE id=?", disabled);
            long ghost = user(tag + "_g", "未选课", 2);
            users.addAll(List.of(teacher, s1, s2, s3, s4, disabled, ghost));
            course = insert("INSERT INTO course (code, name, teacher_id, status) VALUES (?,?,?,1)", tag, "周报IT课", teacher);
            long c = course;
            long n1 = insert("INSERT INTO kg_node (course_id, node_code, name, chapter) VALUES (?,?,?,?)", c, "N1", "循环", "第一章");
            long n2 = insert("INSERT INTO kg_node (course_id, node_code, name, chapter) VALUES (?,?,?,?)", c, "N2", "变量", "第一章");
            long n3 = insert("INSERT INTO kg_node (course_id, node_code, name, chapter, archived_at) VALUES (?,?,?,?,NOW())",
                    c, "N3", "已归档", "第二章");
            for (long s : List.of(s1, s2, s3, s4, disabled)) {
                jdbc.update("INSERT INTO student_course (student_id, course_id) VALUES (?,?)", s, c);
            }
            for (long[] row : new long[][]{{s1, n1, 20}, {s1, n2, 90}, {s1, n3, 5}, {s2, n1, 40}, {s2, n2, 80},
                    {disabled, n1, 0}, {ghost, n1, 0}}) {
                jdbc.update("INSERT INTO student_mastery (student_id, course_id, node_id, mastery_score, mastery_level)"
                        + " VALUES (?,?,?,?,?)", row[0], c, row[1], row[2], row[2] >= 75 ? 2 : row[2] >= 40 ? 1 : 0);
            }
            // s1 was at 90 on N1 ten days ago -> 7-day effective drop
            jdbc.update("INSERT INTO mastery_snapshot (student_id, course_id, node_id, mastery_score, mastery_level, snapshot_at)"
                    + " VALUES (?,?,?,90,2,?)", s1, c, n1, LocalDateTime.now().minusDays(10));

            questionId = insert("INSERT INTO question (course_id, stem, stem_hash, type, difficulty, answer, status) VALUES (?,?,?,1,1,'A',1)",
                    c, "周报题", "%064d".formatted(Math.abs((tag).hashCode())));
            LocalDate today = LocalDate.now();
            LocalDate start = today.minusDays(7);
            LocalDate end = today.minusDays(1);
            // this week: s1 x3, s2 x2 (+ disabled student's answer must not count); last week: s1 x1
            answer(s1, c, 2);
            answer(s1, c, 3);
            answer(s1, c, 4);
            answer(s2, c, 2);
            answer(s2, c, 5);
            answer(disabled, c, 3);
            answer(s1, c, 10);
            answer(s4, c, 20);
            // companion: one conversation with a student message this week, one last week
            long conv = insert("INSERT INTO companion_conversation (student_id, course_id, title) VALUES (?,?,?)", s1, c, "t");
            jdbc.update("INSERT INTO companion_message (conversation_id, role, content, created_at) VALUES (?,1,'PRIVATE-CONTENT',?)",
                    conv, LocalDateTime.now().minusDays(2));
            long conv2 = insert("INSERT INTO companion_conversation (student_id, course_id, title) VALUES (?,?,?)", s2, c, "t2");
            jdbc.update("INSERT INTO companion_message (conversation_id, role, content, created_at) VALUES (?,1,'PRIVATE-2',?)",
                    conv2, LocalDateTime.now().minusDays(9));

            long reportId = reportService.buildForCourse(c, start, end);
            String json = jdbc.queryForObject("SELECT payload_json FROM agent_report WHERE id=?", String.class, reportId);
            JsonNode p = om.readTree(json);

            assertThat(p.path("rosterSize").asInt()).isEqualTo(4); // disabled student excluded
            JsonNode weak = p.path("weakNodes");
            assertThat(weak.get(0).path("nodeCode").asText()).isEqualTo("N1");
            assertThat(weak.get(0).path("name").asText()).isEqualTo("循环");
            assertThat(weak.get(0).path("sampleSize").asInt()).isEqualTo(2); // disabled / ghost excluded
            assertThat(weak.get(0).path("avgEffective").asDouble()).isBetween(0.25, 0.35);
            for (JsonNode w : weak) {
                assertThat(w.path("nodeCode").asText()).isNotEqualTo("N3"); // archived node excluded
            }
            List<String> reasons = new ArrayList<>();
            p.path("anomalies").forEach(a -> reasons.add(a.path("type").asText() + ":" + a.path("studentName").asText()));
            assertThat(reasons).contains("EFFECTIVE_DROP:甲同学", "NO_PRACTICE:丙同学", "NO_PRACTICE:丁同学")
                    .noneMatch(r -> r.contains("禁用生") || r.contains("未选课"));
            JsonNode eng = p.path("engagement");
            assertThat(eng.path("practiceThisWeek").asInt()).isEqualTo(5);
            assertThat(eng.path("practiceLastWeek").asInt()).isEqualTo(1);
            assertThat(eng.path("practiceDelta").asInt()).isEqualTo(4);
            assertThat(eng.path("activeStudentsThisWeek").asInt()).isEqualTo(2);
            assertThat(eng.path("activeStudentsLastWeek").asInt()).isEqualTo(1);
            assertThat(eng.path("dialogueThisWeek").asInt()).isEqualTo(1);
            assertThat(eng.path("dialogueLastWeek").asInt()).isEqualTo(1);
            assertThat(p.path("summary").asText()).contains("共 4 名学生", "作答 5 次（↑4）");
            assertThat(json).doesNotContain("PRIVATE-").doesNotContain("Infinity");

            // idempotent: second run returns the same row and does not duplicate
            assertThat(reportService.buildForCourse(c, start, end)).isEqualTo(reportId);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_report WHERE course_id=?", Integer.class, c))
                    .isEqualTo(1);
        } finally {
            if (course != null) {
                long c = course;
                jdbc.update("DELETE FROM agent_report WHERE course_id=?", c);
                jdbc.update("DELETE m FROM companion_message m JOIN companion_conversation v ON v.id=m.conversation_id WHERE v.course_id=?", c);
                jdbc.update("DELETE FROM companion_conversation WHERE course_id=?", c);
                jdbc.update("DELETE FROM answer_record WHERE course_id=?", c);
                jdbc.update("DELETE FROM mastery_snapshot WHERE course_id=?", c);
                jdbc.update("DELETE FROM student_mastery WHERE course_id=?", c);
                jdbc.update("DELETE FROM student_course WHERE course_id=?", c);
                jdbc.update("DELETE FROM question WHERE course_id=?", c);
                jdbc.update("DELETE FROM kg_node WHERE course_id=?", c);
                jdbc.update("DELETE FROM course WHERE id=?", c);
            }
            for (Long u : users) {
                jdbc.update("DELETE FROM sys_user WHERE id=?", u);
            }
        }
    }
}
