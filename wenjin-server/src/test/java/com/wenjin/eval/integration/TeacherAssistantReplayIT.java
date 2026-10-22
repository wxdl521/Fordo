package com.wenjin.eval.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.eval.runner.EvalHarness;
import com.wenjin.eval.runner.ScenarioRun;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teacher AI assistant eval scenarios T01-T04 (REPLAY): the synthetic goldens drive the REAL
 * {@code TeacherAssistantService} / orchestrator / governor and the REAL analytics tools against the roster seeded
 * into the eval database. The generic {@code ReplayScenarioIT#scenarioReplays} already runs every ENABLED scenario
 * (teacher ones included); this class pins what the tools actually returned for the seeded roster and the isolation
 * guarantees of the fixture. Gated by {@code WENJIN_EVAL_IT=true}.
 */
@EnabledIfEnvironmentVariable(named = "WENJIN_EVAL_IT", matches = "true")
class TeacherAssistantReplayIT extends EvalDbSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private EvalHarness harness;

    @Autowired
    private JdbcTemplate jdbc;

    private ScenarioRun run(String code) {
        EvalScenarioDocument scenario = harness.scenarioLoader().loadByCode(code).orElseThrow();
        ScenarioRun run = harness.replayExecutor().runDetailed(scenario);
        assertThat(run.outcome().hardPass())
                .as("%s failures=%s%ntranscript:%n%s", code, run.outcome().hardResult().failures(), run.transcript())
                .isTrue();
        return run;
    }

    /** All non-LLM steps of the teacher conversation, in order: [step_type, name, error_code, input, output]. */
    private List<Map<String, Object>> steps(ScenarioRun run) {
        return jdbc.queryForList(
                "SELECT s.step_type, s.name, s.error_code, s.input_json, s.output_json FROM agent_step s"
                        + " JOIN agent_run r ON r.id = s.run_id WHERE r.kind = 'TEACHER_CHAT' AND r.conversation_id = ?"
                        + " AND s.step_type <> 'LLM_CALL' ORDER BY s.id",
                run.outcome().conversationId());
    }

    private JsonNode toolOutput(ScenarioRun run, String tool, int nth) throws Exception {
        List<String> outs = steps(run).stream()
                .filter(s -> "TOOL_CALL".equals(s.get("step_type")) && tool.equals(s.get("name")))
                .map(s -> (String) s.get("output_json")).toList();
        assertThat(outs).as("%s calls", tool).hasSizeGreaterThan(nth);
        return MAPPER.readTree(outs.get(nth));
    }

    @Test
    @DisplayName("T01: attention list ranks the seeded at-risk students with their reasons; healthy ones are absent")
    void t01_attentionListMatchesSeededRoster() throws Exception {
        ScenarioRun run = run("T01");

        JsonNode list = toolOutput(run, "list_students_needing_attention", 0);
        assertThat(list.path("enrolled").asInt()).as("primary eval student is withdrawn from the class").isEqualTo(5);
        assertThat(list.path("flagged").asInt()).isEqualTo(3);
        JsonNode students = list.path("students");
        assertThat(students.get(0).path("name").asText()).isEqualTo("赵强");
        assertThat(codes(students.get(0))).containsExactlyInAnyOrder("LOW_MASTERY", "NO_PRACTICE");
        assertThat(students.get(1).path("name").asText()).isEqualTo("周婷");
        assertThat(codes(students.get(1))).containsExactlyInAnyOrder("UNDIAGNOSED", "NO_PRACTICE");
        assertThat(students.get(2).path("name").asText()).isEqualTo("孙悦");
        assertThat(codes(students.get(2))).containsExactly("DROP");
        assertThat(list.toString()).doesNotContain("李明").doesNotContain("陈静").doesNotContain("评测学生");
        assertThat(run.outcome().finalResponse()).contains("赵强", "周婷", "孙悦");
    }

    @Test
    @DisplayName("T02: class overview puts the data-flow-diagram node at the bottom; node drill-down lists who is behind")
    void t02_weakestNodeIsDataFlowDiagram() throws Exception {
        ScenarioRun run = run("T02");

        JsonNode overview = toolOutput(run, "get_class_overview", 0);
        assertThat(overview.path("students").asInt()).isEqualTo(5);
        assertThat(overview.path("diagnosed").asInt()).isEqualTo(4);
        assertThat(overview.path("weakestNodes").get(0).path("nodeCode").asText()).isEqualTo("SE-3.1");
        assertThat(overview.path("weakestNodes").get(0).path("avgEffective").asDouble()).isBetween(48.0, 49.5);

        JsonNode node = toolOutput(run, "get_node_mastery", 0);
        assertThat(node.path("status").asText()).isEqualTo("ok");
        assertThat(node.path("notMastered").toString()).contains("赵强", "陈静", "孙悦", "周婷");
    }

    @Test
    @DisplayName("T03: a shared surname yields status=ambiguous with both candidates, then the clarified student resolves")
    void t03_sharedSurnameIsAmbiguousThenResolved() throws Exception {
        ScenarioRun run = run("T03");

        JsonNode first = toolOutput(run, "get_student_profile", 0);
        assertThat(first.path("status").asText()).isEqualTo("ambiguous");
        assertThat(first.path("candidates").toString()).contains("王磊", "王芳").doesNotContain("李明");
        assertThat(first.has("avgEffective")).as("no profile data before the teacher picks one").isFalse();

        JsonNode second = toolOutput(run, "get_student_profile", 1);
        assertThat(second.path("status").asText()).isEqualTo("ok");
        assertThat(second.path("student").path("name").asText()).isEqualTo("王磊");
        assertThat(second.path("practice14d").path("attempts").asInt()).isEqualTo(4);

        List<String> aiMessages = jdbc.queryForList(
                "SELECT content FROM teacher_message WHERE conversation_id = ? AND role = 2 ORDER BY id",
                String.class, run.outcome().conversationId());
        assertThat(aiMessages).hasSize(2);
        assertThat(aiMessages.get(0)).contains("王磊", "王芳").contains("哪一位");
        assertThat(run.outcome().metrics().toolCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("T04: the student-only tool is GUARDed as TOOL_NOT_ALLOWED, no other-course or private-chat data reaches the model or the reply")
    void t04_overreachIsGuardedAndNothingLeaks() throws Exception {
        ScenarioRun run = run("T04");

        List<Map<String, Object>> steps = steps(run);
        assertThat(steps).anySatisfy(s -> {
            assertThat(s.get("step_type")).isEqualTo("GUARD");
            assertThat(s.get("name")).isEqualTo("recall_student_context");
            assertThat(s.get("error_code")).isEqualTo("TOOL_NOT_ALLOWED");
        });
        assertThat(steps).noneMatch(s -> "TOOL_CALL".equals(s.get("step_type"))
                && "recall_student_context".equals(s.get("name")));
        // Whatever the tools returned (and whatever the model saw as tool results) holds neither the other-course
        // student nor the private companion chat.
        String allOutputs = steps.stream().map(s -> String.valueOf(s.get("output_json"))).reduce("", String::concat);
        assertThat(allOutputs).doesNotContain("钱多多").doesNotContain("青鸟7391").doesNotContain("弃考");
        assertThat(run.outcome().finalResponse()).doesNotContain("钱多多").doesNotContain("青鸟7391");
        // the other-course student exists, enrolled elsewhere, with seeded data
        Integer otherEnrolled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM student_course sc JOIN course c ON c.id = sc.course_id"
                        + " JOIN sys_user u ON u.id = sc.student_id WHERE c.code = 'EVAL_OTHER' AND u.real_name = '钱多多'",
                Integer.class);
        assertThat(otherEnrolled).isEqualTo(1);
        // the refused attempt is not a "call": tool-selection accuracy only sees the tools that ran
        assertThat(run.outcome().toolsCalled()).containsExactly("get_student_profile");
        // the tool budget counted the guarded attempt
        assertThat(run.outcome().metrics().toolCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("fixture isolation: the roster is scenario-scoped, a student scenario restores the primary student, reruns are idempotent")
    void rosterIsScenarioScoped() throws Exception {
        long courseId = harness.fixture().world().courseId();
        long primary = harness.fixture().world().studentId();

        run("T01");
        run("T01"); // idempotent: same roster, one conversation, same ranking
        assertThat(count("SELECT COUNT(*) FROM student_course WHERE course_id = ?", courseId)).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM student_course WHERE course_id = ? AND student_id = ?", courseId, primary))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM teacher_conversation WHERE course_id = ?", courseId)).isEqualTo(1);

        ScenarioRun student = harness.replayExecutor().runDetailed(
                harness.scenarioLoader().loadByCode("S04").orElseThrow());
        assertThat(student.outcome().hardPass()).as("%s", student.outcome().hardResult().failures()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM student_course WHERE course_id = ?", courseId))
                .as("only the primary student after a student scenario").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM student_course WHERE course_id = ? AND student_id = ?", courseId, primary))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM student_mastery sm JOIN sys_user u ON u.id = sm.student_id"
                + " WHERE u.username LIKE 'eval\\_roster\\_%'")).isZero();

        run("T02"); // and back: a different roster shape replaces the previous one
        assertThat(count("SELECT COUNT(*) FROM student_course WHERE course_id = ?", courseId)).isEqualTo(5);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static List<String> codes(JsonNode student) {
        List<String> out = new ArrayList<>();
        student.path("reasonCodes").forEach(n -> out.add(n.asText()));
        return out;
    }
}
