package com.wenjin.eval.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.service.companion.ToolContext;
import com.wenjin.service.companion.ToolExecutionResult;
import com.wenjin.service.teacher.TeacherAnalyticsModels.*;
import com.wenjin.service.teacher.TeacherAnalyticsService;
import com.wenjin.service.teacher.tool.GetClassOverviewTool;
import com.wenjin.service.teacher.tool.ListStudentsNeedingAttentionTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teacher analytics SQL end-to-end on MySQL: seeds a throw-away course (4 enrolled students, 1 non-enrolled
 * student, objective + CODE questions) and cleans everything up in {@code finally}.
 * Gated by {@code WENJIN_EVAL_IT=true}.
 */
@EnabledIfEnvironmentVariable(named = "WENJIN_EVAL_IT", matches = "true")
class TeacherAnalyticsIT extends EvalDbSupport {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TeacherAnalyticsService service;
    @Autowired private GetClassOverviewTool overviewTool;
    @Autowired private ListStudentsNeedingAttentionTool attentionTool;
    @Autowired private ObjectMapper om;

    private final List<String> cleanup = new ArrayList<>();

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

    private void answer(long student, long course, long question, String ans, int correct, int daysAgo, int scene) {
        jdbc.update("INSERT INTO answer_record (student_id, course_id, question_id, student_answer, is_correct, answered_at, scene)"
                + " VALUES (?,?,?,?,?, DATE_SUB(NOW(), INTERVAL ? DAY), ?)", student, course, question, ans, correct, daysAgo, scene);
    }

    private long question(long course, String stem, int type, long nodeId, String correctKey, String... wrongKeysWithPoint) {
        long q = insert("INSERT INTO question (course_id, stem, stem_hash, type, difficulty, answer, status) VALUES (?,?,?,?,1,?,1)",
                course, stem, "%064d".formatted(Math.abs((stem + course).hashCode())), type, correctKey);
        jdbc.update("INSERT INTO question_node (question_id, node_id, weight) VALUES (?,?,1)", q, nodeId);
        if (type != 5) {
            jdbc.update("INSERT INTO question_option (question_id, option_key, option_text, is_correct) VALUES (?,?,?,1)",
                    q, correctKey, "正确项");
            for (String w : wrongKeysWithPoint) {
                String[] kv = w.split(":");
                jdbc.update("INSERT INTO question_option (question_id, option_key, option_text, is_correct, point_node_code)"
                        + " VALUES (?,?,?,0,?)", q, kv[0], "干扰" + kv[0], kv.length > 1 ? kv[1] : null);
            }
        }
        return q;
    }

    @Test
    @DisplayName("overview / attention / profile / node / question stats / trend over real SQL; non-enrolled invisible")
    void analyticsEndToEnd() throws Exception {
        String tag = "ta" + (System.nanoTime() % 100000000L);
        Long course = null;
        List<Long> users = new ArrayList<>();
        try {
            long teacher = user(tag + "_t", "老师", 1);
            long s1 = user(tag + "_s1", "甲同学", 2);
            long s2 = user(tag + "_s2", "乙同学", 2);
            long s3 = user(tag + "_s3", "甲同学二号", 2);
            long s4 = user(tag + "_s4", null, 2);
            long ghost = user(tag + "_g", "幽灵", 2);
            users.addAll(List.of(s1, s2, s3, s4, ghost, teacher));
            course = insert("INSERT INTO course (code, name, teacher_id, status) VALUES (?,?,?,1)", tag, "IT课", teacher);
            long c = course;
            long n1 = insert("INSERT INTO kg_node (course_id, node_code, name, chapter) VALUES (?,?,?,?)", c, "N1", "变量", "第一章");
            long n2 = insert("INSERT INTO kg_node (course_id, node_code, name, chapter) VALUES (?,?,?,?)", c, "N2", "循环", "第一章");
            long n3 = insert("INSERT INTO kg_node (course_id, node_code, name, chapter) VALUES (?,?,?,?)", c, "N3", "类", "第二章");
            for (long s : List.of(s1, s2, s3, s4)) {
                jdbc.update("INSERT INTO student_course (student_id, course_id) VALUES (?,?)", s, c);
            }
            // mastery: s1 strong, s2 weak, s3 partial; s4 none; ghost (not enrolled) has data that must not show
            for (long[] row : new long[][]{{s1, n1, 70}, {s1, n2, 85}, {s1, n3, 80}, {s2, n1, 20}, {s2, n2, 30},
                    {s2, n3, 10}, {s3, n1, 60}, {ghost, n1, 0}}) {
                jdbc.update("INSERT INTO student_mastery (student_id, course_id, node_id, mastery_score, mastery_level)"
                        + " VALUES (?,?,?,?,?)", row[0], c, row[1], row[2], row[2] >= 75 ? 2 : row[2] >= 40 ? 1 : 0);
            }
            // snapshot: s1 was at 100 on N1 eight days ago -> 7-day drop
            jdbc.update("INSERT INTO mastery_snapshot (student_id, course_id, node_id, mastery_score, mastery_level, snapshot_at)"
                    + " VALUES (?,?,?,100,2, DATE_SUB(NOW(), INTERVAL 8 DAY))", s1, c, n1);

            long qa = question(c, "题A", 1, n2, "A", "B:N1", "C:N2", "D");
            long qb = question(c, "题B(样本不足)", 1, n2, "A", "B:N1");
            long qcode = question(c, "编程题", 5, n2, "-");
            // qa: 6 enrolled attempts, 4 wrong (B,B,C,B) ; 1 ghost attempt must not count
            answer(s1, c, qa, "A", 1, 1, 2);
            answer(s2, c, qa, "B", 0, 1, 2);
            answer(s2, c, qa, "B", 0, 2, 2);
            answer(s3, c, qa, "C", 0, 2, 2);
            answer(s3, c, qa, "B", 0, 3, 1);
            answer(s1, c, qa, "A", 1, 3, 1);
            answer(ghost, c, qa, "B", 0, 1, 2);
            // qb: only 3 attempts -> gated
            answer(s2, c, qb, "B", 0, 1, 2);
            answer(s3, c, qb, "B", 0, 1, 2);
            answer(s1, c, qb, "B", 0, 1, 2);
            // CODE questions never enter distractor stats; 6 wrong code answers by s3 -> MANY_WRONG
            for (int i = 0; i < 6; i++) {
                answer(s3, c, qcode, "public class A{}", 0, 1, 2);
            }
            // s2 last answered 20 days ago via an old answer (outside windows)
            answer(s2, c, qb, "B", 0, 20, 2);
            // companion activity (count only)
            long conv = insert("INSERT INTO companion_conversation (student_id, course_id, title) VALUES (?,?,?)", s1, c, "t");
            jdbc.update("INSERT INTO companion_message (conversation_id, role, content) VALUES (?,1,'PRIVATE-CONTENT')", conv);
            jdbc.update("INSERT INTO companion_message (conversation_id, role, content) VALUES (?,2,'reply')", conv);

            // ── overview
            ClassOverview o = service.classOverview(c);
            assertThat(o.students()).isEqualTo(4);
            assertThat(o.diagnosed()).isEqualTo(3);
            assertThat(o.distribution()).isEqualTo(new StudentDistribution(1, 1, 1, 1));
            assertThat(o.weakestNodes().get(0).nodeCode()).isEqualTo("N3");
            assertThat(o.active7d()).isEqualTo(3);

            // ── attention
            AttentionList list = service.studentsNeedingAttention(c, 20, null);
            assertThat(list.students()).extracting(AttentionItem::studentId).doesNotContain(ghost);
            AttentionItem i2 = list.students().stream().filter(x -> x.studentId() == s2).findFirst().orElseThrow();
            assertThat(i2.reasonCodes()).contains("LOW_MASTERY");
            AttentionItem i3 = list.students().stream().filter(x -> x.studentId() == s3).findFirst().orElseThrow();
            assertThat(i3.reasonCodes()).contains("MANY_WRONG");
            AttentionItem i4 = list.students().stream().filter(x -> x.studentId() == s4).findFirst().orElseThrow();
            assertThat(i4.reasonCodes()).contains("UNDIAGNOSED", "NO_PRACTICE");
            assertThat(i4.name()).isEqualTo(tag.charAt(0) + "***" + "4");
            AttentionList drop = service.studentsNeedingAttention(c, 20, "DROP");
            assertThat(drop.students()).extracting(AttentionItem::studentId).containsExactly(s1);

            // ── profile: fuzzy name ambiguity and id lookup; ghost invisible
            StudentProfile amb = service.studentProfile(c, "甲同学");
            assertThat(amb.status()).isEqualTo("ok"); // exact name wins
            assertThat(amb.student().studentId()).isEqualTo(s1);
            assertThat(service.studentProfile(c, "甲").status()).isEqualTo("ambiguous");
            assertThat(service.studentProfile(c, "幽灵").status()).isEqualTo("not_found");
            StudentProfile p2 = service.studentProfile(c, String.valueOf(s2));
            assertThat(p2.practice14d().attempts()).isEqualTo(3);
            assertThat(p2.weakestNodes().get(0).nodeCode()).isEqualTo("N3");

            // ── node mastery
            NodeMasteryResult nm = service.nodeMastery(c, "N1");
            assertThat(nm.distribution()).isEqualTo(new StudentDistribution(0, 2, 1, 1));
            assertThat(nm.notMastered()).extracting(NodeStudentView::studentId).containsExactly(s2, s3, s1, s4);

            // ── question error stats: qa ranked (4/6 wrong, in-window), qb gated, CODE excluded, ghost excluded
            QuestionErrorStats qs = service.questionErrorStats(c, null, 10);
            assertThat(qs.questions()).hasSize(1);
            QuestionStat q = qs.questions().get(0);
            assertThat(q.questionId()).isEqualTo(qa);
            assertThat(q.attempts()).isEqualTo(6);
            assertThat(q.wrong()).isEqualTo(4);
            assertThat(q.distractors().get(0).key()).isEqualTo("B");
            assertThat(q.distractors().get(0).count()).isEqualTo(3);
            assertThat(q.distractors().get(0).misconceptionNode().nodeCode()).isEqualTo("N1");
            assertThat(service.questionErrorStats(c, "N1", 10).questions()).isEmpty();
            assertThat(service.questionErrorStats(c, "N2", 10).questions()).hasSize(1);

            // ── trend
            ActivityTrend t = service.activityTrend(c, 7);
            assertThat(t.daily()).hasSize(7);
            assertThat(t.totalAnswers()).isEqualTo(6 + 3 + 6); // enrolled answers within 7 days (ghost + 20d-old excluded)
            assertThat(t.daily().stream().mapToInt(DayStat::companionConversations).sum()).isEqualTo(1);

            // ── tools end-to-end (bean wiring + JSON never contains chat content)
            ToolExecutionResult r = overviewTool.executeForGovernor("c1", ToolContext.forTeacher(teacher, c),
                    om.createObjectNode());
            JsonNode j = om.readTree(r.content());
            assertThat(j.path("students").asInt()).isEqualTo(4);
            assertThat(r.content()).doesNotContain("PRIVATE-CONTENT");
            ToolExecutionResult ar = attentionTool.executeForGovernor("c2", ToolContext.forTeacher(teacher, c),
                    om.readTree("{\"limit\":2}"));
            assertThat(ar.insightCard().rows()).hasSize(2);
        } finally {
            if (course != null) {
                long c = course;
                jdbc.update("DELETE m FROM companion_message m JOIN companion_conversation v ON v.id=m.conversation_id WHERE v.course_id=?", c);
                jdbc.update("DELETE FROM companion_conversation WHERE course_id=?", c);
                jdbc.update("DELETE FROM answer_record WHERE course_id=?", c);
                jdbc.update("DELETE FROM mastery_snapshot WHERE course_id=?", c);
                jdbc.update("DELETE FROM student_mastery WHERE course_id=?", c);
                jdbc.update("DELETE FROM student_course WHERE course_id=?", c);
                jdbc.update("DELETE o FROM question_option o JOIN question q ON q.id=o.question_id WHERE q.course_id=?", c);
                jdbc.update("DELETE n FROM question_node n JOIN question q ON q.id=n.question_id WHERE q.course_id=?", c);
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
