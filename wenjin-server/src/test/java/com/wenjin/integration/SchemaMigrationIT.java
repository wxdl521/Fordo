package com.wenjin.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 受控迁移两条路径：
 * <ul>
 *   <li>空库：执行 V1…V17 建出全部业务表与后续增量，二次 migrate 无变更且 checksum 稳定</li>
 *   <li>遗留库：先加载 V1 级结构（m01/m02），baseline version 2 接管，执行 V3+（含 V4–V17）</li>
 * </ul>
 * <p>说明：本地 {@code schema.sql} 是「当前最终 DDL」快照（已含 V4–V17 对象与物理 FK），不能直接当作
 * 遗留库输入，否则 baseline@2 后重跑 V4+ 会因列已存在而失败。遗留路径因此以
 * {@code V1__baseline_schema.sql} 模拟接入 Flyway 前的生产库。
 */
class SchemaMigrationIT extends MySqlContainerSupport {

    /** V1 业务表（不含 flyway_schema_history；与 m01/m02 遗留结构对齐）。 */
    private static final List<String> BASELINE_TABLES = List.of(
            "sys_user",
            "course",
            "kg_node",
            "kg_edge",
            "question",
            "question_option",
            "question_node",
            "student_mastery",
            "mastery_snapshot",
            "answer_record",
            "learning_path",
            "learning_path_item",
            "practice_session",
            "adaptive_diag_session",
            "resource",
            "node_resource",
            "student_course",
            "companion_conversation",
            "companion_message",
            "extraction_review"
    );

    private static final List<String> V3_TABLES = List.of(
            "diagnostic_session",
            "diagnostic_session_question"
    );

    private static final List<String> V7_TABLES = List.of("ai_job");

    private static final List<String> V10_TABLES = List.of("agent_run", "agent_step");

    private static final List<String> V11_TABLES = List.of("eval_scenario", "eval_case_result");

    private static final List<String> V12_TABLES = List.of("student_memory");

    private static final List<String> V13_TABLES = List.of("doc_source", "doc_chunk", "chunk_node_map");

    private static final List<String> V14_TABLES = List.of("agent_plan", "agent_plan_task", "tool_effect_key");

    private static final List<String> V15_TABLES = List.of("agent_report");

    private static final List<String> V16_TABLES = List.of("audit_log", "login_log");

    private static final List<String> V17_TABLES = List.of("clazz", "clazz_student", "clazz_course");

    /** 空库跑完 V1…V17 后应有的业务表（V4–V6/V8–V9 为 ALTER；V7 增 ai_job；V10 增 agent_*；V11 增 eval_*；V12 增 student_memory；V13 增 RAG 三表；V14 增 plan 三表；V15 增 agent_report；V16 增 audit_log / login_log；V17 增班级三表）。 */
    private static final List<String> EXPECTED_TABLES_AFTER_ALL;

    /** V9 完成后必须存在的完整命名外键矩阵（V9 42 个 + V3 2 个 + V5 1 个）。 */
    private static final List<String> EXPECTED_FKS_AFTER_V9 = List.of(
            "fk_course_teacher",
            "fk_node_course",
            "fk_edge_course",
            "fk_edge_from_node",
            "fk_edge_to_node",
            "fk_question_course",
            "fk_option_question",
            "fk_qn_question",
            "fk_qn_node",
            "fk_mastery_student",
            "fk_mastery_course",
            "fk_mastery_node",
            "fk_snapshot_student",
            "fk_snapshot_course",
            "fk_snapshot_node",
            "fk_path_student",
            "fk_path_course",
            "fk_path_target_node",
            "fk_path_item_path",
            "fk_path_item_node",
            "fk_practice_student",
            "fk_practice_course",
            "fk_practice_node",
            "fk_practice_path_item",
            "fk_adaptive_student",
            "fk_adaptive_course",
            "fk_answer_student",
            "fk_answer_course",
            "fk_answer_question",
            "fk_answer_fixed_diag",
            "fk_node_resource_node",
            "fk_node_resource_resource",
            "fk_sc_student",
            "fk_sc_course",
            "fk_sc_current_path",
            "fk_companion_student",
            "fk_companion_course",
            "fk_companion_msg_conv",
            "fk_extraction_course",
            "fk_ai_job_owner",
            "fk_ai_job_course",
            "fk_diag_student",
            "fk_diag_course",
            "fk_dsq_session",
            "fk_dsq_question_course"
    );

    /** V17 班级三表新增的物理外键（V10–V16 均无物理 FK）。 */
    private static final List<String> V17_FKS = List.of(
            "fk_cs_clazz",
            "fk_cs_student",
            "fk_cc_clazz",
            "fk_cc_course"
    );

    /** 跑完全部迁移（或由 schema.sql 重建）后的完整命名外键矩阵。 */
    private static final List<String> EXPECTED_FKS_AFTER_ALL;

    static {
        List<String> all = new ArrayList<>(BASELINE_TABLES);
        all.addAll(V3_TABLES);
        all.addAll(V7_TABLES);
        all.addAll(V10_TABLES);
        all.addAll(V11_TABLES);
        all.addAll(V12_TABLES);
        all.addAll(V13_TABLES);
        all.addAll(V14_TABLES);
        all.addAll(V15_TABLES);
        all.addAll(V16_TABLES);
        all.addAll(V17_TABLES);
        EXPECTED_TABLES_AFTER_ALL = List.copyOf(all);

        List<String> fks = new ArrayList<>(EXPECTED_FKS_AFTER_V9);
        fks.addAll(V17_FKS);
        EXPECTED_FKS_AFTER_ALL = List.copyOf(fks);
    }

    @BeforeEach
    void wipeSchema() throws Exception {
        try (Connection conn = MYSQL.createConnection("");
             Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            List<String[]> routines = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT routine_type, routine_name FROM information_schema.routines "
                            + "WHERE routine_schema = DATABASE()")) {
                while (rs.next()) {
                    routines.add(new String[]{rs.getString(1), rs.getString(2)});
                }
            }
            for (String table : tables) {
                st.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
            for (String[] routine : routines) {
                st.execute("DROP " + routine[0] + " IF EXISTS `" + routine[1] + "`");
            }
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    @Test
    @DisplayName("空库：V1…V17 建出全部表；二次 migrate 无变更且 checksum 稳定；V9 约束、V10–V17 就绪")
    void emptyDatabase_appliesV1ThroughV17_idempotent() throws Exception {
        Flyway flyway = createFlyway();

        MigrateResult first = flyway.migrate();
        assertThat(first.success).isTrue();
        assertThat(first.migrationsExecuted).isEqualTo(17);

        assertThat(listBusinessTables()).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES_AFTER_ALL);
        Map<String, Integer> checksumsAfterFirst = successChecksums();
        assertThat(checksumsAfterFirst.keySet())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17");
        assertThat(checksumsAfterFirst.get("1")).isNotNull();
        assertThat(checksumsAfterFirst.get("2")).isNotNull();
        assertThat(checksumsAfterFirst.get("3")).isNotNull();
        assertThat(checksumsAfterFirst.get("4")).isNotNull();
        assertThat(checksumsAfterFirst.get("5")).isNotNull();
        assertThat(checksumsAfterFirst.get("6")).isNotNull();
        assertThat(checksumsAfterFirst.get("7")).isNotNull();
        assertThat(checksumsAfterFirst.get("8")).isNotNull();
        assertThat(checksumsAfterFirst.get("9")).isNotNull();
        assertThat(checksumsAfterFirst.get("10")).isNotNull();
        assertThat(checksumsAfterFirst.get("11")).isNotNull();
        assertThat(checksumsAfterFirst.get("12")).isNotNull();
        assertThat(checksumsAfterFirst.get("13")).isNotNull();
        assertThat(checksumsAfterFirst.get("14")).isNotNull();
        assertThat(checksumsAfterFirst.get("15")).isNotNull();
        assertThat(checksumsAfterFirst.get("16")).isNotNull();
        assertThat(checksumsAfterFirst.get("17")).isNotNull();
        assertThat(columnExists("companion_conversation", "summary_text")).isTrue();
        assertThat(columnExists("companion_conversation", "summary_through_message_id")).isTrue();
        assertThat(columnExists("companion_conversation", "distilled_through_message_id")).isTrue();
        assertThat(columnExists("companion_conversation", "summary_origin")).isTrue();
        assertThat(columnExists("student_memory", "salience")).isTrue();
        assertThat(columnExists("student_memory", "last_reinforced_at")).isTrue();
        assertThat(columnExists("doc_source", "kind")).isTrue();
        assertThat(columnExists("doc_chunk", "content")).isTrue();
        assertThat(columnExists("chunk_node_map", "review_status")).isTrue();
        assertThat(constraintExists("doc_chunk", "uk_chunk_seq")).isTrue();
        assertThat(constraintExists("doc_chunk", "ft_chunk_content")).isTrue();
        assertThat(constraintExists("chunk_node_map", "uk_map_chunk_node")).isTrue();
        assertThat(columnExists("kg_node", "archived_by_course")).isTrue();
        assertThat(columnExists("kg_edge", "archived_by_course")).isTrue();
        assertThat(columnNullable("kg_node", "archived_by_course")).isFalse();
        assertThat(columnNullable("kg_edge", "archived_by_course")).isFalse();
        assertThat(constraintExists("kg_node", "chk_node_course_archive_marker")).isTrue();
        assertThat(constraintExists("kg_edge", "chk_edge_course_archive_marker")).isTrue();
        assertThat(columnNullable("course", "teacher_id")).isFalse();
        assertThat(columnNullable("question", "stem_hash")).isFalse();
        assertThat(constraintExists("sys_user", "chk_sys_user_role")).isTrue();
        assertThat(constraintExists("course", "fk_course_teacher")).isTrue();
        assertThat(constraintExists("kg_node", "fk_node_course")).isTrue();
        assertThat(columnExists("agent_run", "cancel_requested_at")).isTrue();
        assertThat(columnExists("agent_step", "run_id")).isTrue();
        assertThat(columnExists("eval_scenario", "pending_reason")).isTrue();
        assertThat(columnExists("eval_case_result", "batch_id")).isTrue();
        assertThat(columnExists("agent_plan", "target_date")).isTrue();
        assertThat(columnExists("agent_plan_task", "task_kind")).isTrue();
        assertThat(constraintExists("tool_effect_key", "uk_effect")).isTrue();
        assertThat(columnExists("agent_report", "payload_json")).isTrue();
        assertThat(columnExists("agent_report", "period_start")).isTrue();
        assertThat(constraintExists("agent_report", "uk_report_period")).isTrue();
        assertThat(columnExists("audit_log", "operator_name")).isTrue();
        assertThat(columnExists("audit_log", "request_id")).isTrue();
        assertThat(columnNullable("audit_log", "operator_id")).isTrue();
        assertThat(columnNullable("audit_log", "action")).isFalse();
        assertThat(columnExists("login_log", "fail_reason")).isTrue();
        assertThat(columnNullable("login_log", "user_id")).isTrue();
        assertThat(columnExists("sys_user", "token_valid_after")).isTrue();
        assertThat(columnNullable("sys_user", "token_valid_after")).isTrue();
        assertThat(constraintExists("clazz", "uk_clazz_name")).isTrue();
        assertThat(constraintExists("clazz_student", "uk_clazz_student_student")).isTrue();
        assertThat(constraintExists("clazz_course", "uk_clazz_course")).isTrue();
        assertThat(columnNullable("clazz", "description")).isTrue();
        assertThat(foreignKeyConstraintNames())
                .containsExactlyInAnyOrderElementsOf(EXPECTED_FKS_AFTER_ALL);

        MigrateResult second = flyway.migrate();
        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isEqualTo(0);

        Map<String, Integer> checksumsAfterSecond = successChecksums();
        assertThat(checksumsAfterSecond).isEqualTo(checksumsAfterFirst);
        assertThat(countSuccessHistoryRows()).isEqualTo(17);
    }

    @Test
    @DisplayName("本地 schema.sql 可从空库重建最终结构并与归档/FK 契约一致")
    void localSchemaSnapshot_buildsCurrentFinalSchema() throws Exception {
        try (Connection conn = MYSQL.createConnection("")) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema.sql"));
        }

        assertThat(listBusinessTables()).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES_AFTER_ALL);
        assertThat(columnExists("course", "archived_by_course")).isFalse();
        assertThat(columnExists("kg_node", "archived_by_course")).isTrue();
        assertThat(columnExists("kg_edge", "archived_by_course")).isTrue();
        assertThat(columnNullable("kg_node", "archived_by_course")).isFalse();
        assertThat(columnNullable("kg_edge", "archived_by_course")).isFalse();
        assertThat(constraintExists("kg_node", "chk_node_course_archive_marker")).isTrue();
        assertThat(constraintExists("kg_edge", "chk_edge_course_archive_marker")).isTrue();
        assertThat(foreignKeyConstraintNames())
                .containsExactlyInAnyOrderElementsOf(EXPECTED_FKS_AFTER_ALL);
    }

    @Test
    @DisplayName("遗留库：V1 结构 + baseline@2 接管，执行 V3…V17；二次 migrate 无变更")
    void legacySchema_baselinesAtVersion2_onlyFutureMigrations() throws Exception {
        applyLegacyBaselineSchema();
        assertThat(listBusinessTables()).containsExactlyInAnyOrderElementsOf(BASELINE_TABLES);

        Flyway flyway = createFlyway();
        MigrateResult first = flyway.migrate();
        assertThat(first.success).isTrue();
        // baseline@2 后执行 V3…V17
        assertThat(first.migrationsExecuted).isEqualTo(15);

        Map<String, Integer> history = successChecksums();
        // baseline 记录 version=2；不得出现 version=1 的 SUCCESS 迁移；应有 V3–V17
        assertThat(history.keySet()).doesNotContain("1");
        assertThat(history.keySet()).contains("2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17");
        assertThat(listBusinessTables()).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES_AFTER_ALL);
        assertThat(columnExists("companion_conversation", "summary_text")).isTrue();
        assertThat(columnExists("companion_conversation", "distilled_through_message_id")).isTrue();
        assertThat(columnExists("companion_conversation", "summary_origin")).isTrue();
        assertThat(columnExists("student_memory", "content")).isTrue();
        assertThat(columnExists("doc_source", "status")).isTrue();
        assertThat(columnExists("doc_chunk", "seq")).isTrue();
        assertThat(columnExists("chunk_node_map", "node_code")).isTrue();
        assertThat(columnExists("kg_node", "archived_by_course")).isTrue();
        assertThat(columnExists("kg_edge", "archived_by_course")).isTrue();
        assertThat(constraintExists("kg_node", "chk_node_course_archive_marker")).isTrue();
        assertThat(constraintExists("kg_edge", "chk_edge_course_archive_marker")).isTrue();
        assertThat(constraintExists("course", "fk_course_teacher")).isTrue();
        assertThat(columnNullable("course", "teacher_id")).isFalse();
        assertThat(columnExists("agent_run", "kind")).isTrue();
        assertThat(columnExists("agent_step", "step_type")).isTrue();
        assertThat(columnExists("eval_scenario", "code")).isTrue();
        assertThat(columnExists("eval_case_result", "hard_pass")).isTrue();
        assertThat(columnExists("agent_plan", "status")).isTrue();
        assertThat(columnExists("agent_plan_task", "origin")).isTrue();
        assertThat(columnExists("tool_effect_key", "request_key")).isTrue();
        assertThat(columnExists("agent_report", "kind")).isTrue();
        assertThat(constraintExists("agent_report", "uk_report_period")).isTrue();
        assertThat(columnExists("audit_log", "target_id")).isTrue();
        assertThat(columnExists("login_log", "user_agent")).isTrue();
        assertThat(columnExists("sys_user", "token_valid_after")).isTrue();
        assertThat(constraintExists("clazz_student", "uk_clazz_student_student")).isTrue();
        assertThat(constraintExists("clazz_course", "fk_cc_course")).isTrue();

        MigrateResult second = flyway.migrate();
        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isEqualTo(0);
        assertThat(successChecksums()).isEqualTo(history);
    }

    @Test
    @DisplayName("V5 重复路径步骤失败不删数据，repair 后可按同一 target 安全重跑")
    void v5DuplicatePathSteps_failBeforeDeletingRows_thenRepairAndRetry() throws Exception {
        createFlyway("4").migrate();

        executeSql(
                "INSERT INTO sys_user (id, username, password, role) VALUES "
                        + "(101, 'v5-teacher', 'x', 1), (102, 'v5-student', 'x', 2)",
                "INSERT INTO course (id, code, name, teacher_id) "
                        + "VALUES (201, 'V5-C', 'V5 course', 101)",
                "INSERT INTO kg_node (id, course_id, node_code, name) VALUES "
                        + "(301, 201, 'V5-N1', 'node 1'), (302, 201, 'V5-N2', 'node 2')",
                "INSERT INTO learning_path (id, student_id, course_id, status) "
                        + "VALUES (401, 102, 201, 1)",
                "INSERT INTO learning_path_item (id, path_id, node_id, step_order) VALUES "
                        + "(501, 401, 301, 1), (502, 401, 302, 1)"
        );

        Throwable failure = catchThrowable(() -> createFlyway("5").migrate());

        assertThat(failure).isNotNull();
        assertThat(sqlStateFromCauseChain(failure)).isEqualTo("45000");
        assertThat(countRows("learning_path_item", "id IN (501, 502)")).isEqualTo(2);
        assertThat(columnExists("student_course", "current_path_id")).isFalse();
        assertThat(successChecksums().keySet()).containsExactly("1", "2", "3", "4");
        assertThat(migrationHistoryRowExists("5", false)).isTrue();

        executeSql("DELETE FROM learning_path_item WHERE id = 502");
        createFlyway("5").repair();
        MigrateResult retry = createFlyway("5").migrate();

        assertThat(retry.success).isTrue();
        assertThat(retry.migrationsExecuted).isEqualTo(1);
        assertThat(migrationHistoryRowExists("5", false)).isFalse();
        assertThat(migrationHistoryRowExists("5", true)).isTrue();
        assertThat(columnExists("student_course", "current_path_id")).isTrue();
        assertThat(constraintExists("learning_path_item", "uk_path_step_order")).isTrue();
        assertThat(constraintExists("learning_path_item", "uk_path_node")).isTrue();
        assertThat(routineExists("PROCEDURE", "_v5_precheck")).isFalse();
    }

    @Test
    @DisplayName("V6 遇到 trim 后重复题干时以 45000 中止，且不留下 stem_hash 列")
    void v6DuplicateTrimmedStems_failBeforeFirstDdl() throws Exception {
        createFlyway("5").migrate();

        executeSql(
                "INSERT INTO sys_user (id, username, password, role) "
                        + "VALUES (111, 'v6-teacher', 'x', 1)",
                "INSERT INTO course (id, code, name, teacher_id) "
                        + "VALUES (211, 'V6-C', 'V6 course', 111)",
                "INSERT INTO question (id, course_id, stem, type) VALUES "
                        + "(311, 211, 'duplicate stem', 1), "
                        + "(312, 211, '  duplicate stem  ', 1)"
        );

        Throwable failure = catchThrowable(() -> createFlyway("6").migrate());

        assertThat(failure).isNotNull();
        assertThat(sqlStateFromCauseChain(failure)).isEqualTo("45000");
        assertThat(columnExists("question", "stem_hash")).isFalse();
        assertThat(successChecksums().keySet()).containsExactly("1", "2", "3", "4", "5");
        assertThat(routineExists("PROCEDURE", "_v6_precheck"))
                .as("failed V6 leaves a routine that schema cleanup must isolate")
                .isTrue();

        wipeSchema();

        assertThat(listBusinessTables()).isEmpty();
        assertThat(routineExists("PROCEDURE", "_v6_precheck")).isFalse();
    }

    @Test
    @DisplayName("target 8 只执行 V1 到 V8，不提前创建 V9 外键")
    void target8_stopsBeforeV9Contract() throws Exception {
        MigrateResult result = createFlyway("8").migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(8);
        assertThat(successChecksums().keySet())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(constraintExists("course", "fk_course_teacher")).isFalse();
    }

    @Test
    @DisplayName("V9 遇到遗漏表孤儿时在第一条持久 DDL 前以 45000 中止")
    void v9OrphanMasterySnapshot_failsBeforeFirstDdl() throws Exception {
        createFlyway("8").migrate();
        executeSql(
                "INSERT INTO mastery_snapshot "
                        + "(student_id, course_id, node_id, mastery_score, mastery_level) "
                        + "VALUES (991, 992, 993, 50, 1)"
        );

        Throwable failure = catchThrowable(() -> createFlyway("9").migrate());

        assertThat(failure).isNotNull();
        assertThat(failure).hasStackTraceContaining(
                "V9 precheck: orphan mastery_snapshot.student_id");
        assertThat(sqlStateFromCauseChain(failure)).isEqualTo("45000");
        assertThat(countRows("mastery_snapshot", "student_id = 991")).isEqualTo(1);
        assertThat(columnNullable("course", "teacher_id")).isTrue();
        assertThat(constraintExists("course", "fk_course_teacher")).isFalse();
        assertThat(constraintExists("sys_user", "chk_sys_user_role")).isFalse();
        assertThat(migrationHistoryRowExists("9", true)).isFalse();
    }

    @Test
    @DisplayName("V9 遇到快照跨课程错配时在第一条持久 DDL 前以 45000 中止")
    void v9CrossCourseMasterySnapshot_failsBeforeFirstDdl() throws Exception {
        createFlyway("8").migrate();
        executeSql(
                "INSERT INTO sys_user (id, username, password, role, status) VALUES "
                        + "(141, 'v9-scope-teacher', 'x', 1, 1), "
                        + "(142, 'v9-scope-student', 'x', 2, 1)",
                "INSERT INTO course (id, code, name, teacher_id) VALUES "
                        + "(241, 'V9-SCOPE-A', 'scope A', 141), "
                        + "(242, 'V9-SCOPE-B', 'scope B', 141)",
                "INSERT INTO kg_node (id, course_id, node_code, name) "
                        + "VALUES (341, 242, 'V9-SCOPE-N', 'scope node')",
                "INSERT INTO mastery_snapshot "
                        + "(student_id, course_id, node_id, mastery_score, mastery_level) "
                        + "VALUES (142, 241, 341, 50, 1)"
        );

        Throwable failure = catchThrowable(() -> createFlyway("9").migrate());

        assertThat(failure).isNotNull();
        assertThat(failure).hasStackTraceContaining(
                "V9 precheck: cross-course mastery_snapshot.node mismatch");
        assertThat(sqlStateFromCauseChain(failure)).isEqualTo("45000");
        assertThat(countRows("mastery_snapshot", "student_id = 142")).isEqualTo(1);
        assertThat(columnNullable("course", "teacher_id")).isTrue();
        assertThat(constraintExists("course", "fk_course_teacher")).isFalse();
        assertThat(constraintExists("sys_user", "chk_sys_user_role")).isFalse();
        assertThat(migrationHistoryRowExists("9", true)).isFalse();
    }

    @Test
    @DisplayName("V9 遇到练习路径项作用域错配时在第一条持久 DDL 前以 45000 中止")
    void v9PracticePathItemScopeMismatch_failsBeforeFirstDdl() throws Exception {
        createFlyway("8").migrate();
        executeSql(
                "INSERT INTO sys_user (id, username, password, role, status) VALUES "
                        + "(151, 'v9-practice-teacher', 'x', 1, 1), "
                        + "(152, 'v9-path-student', 'x', 2, 1), "
                        + "(153, 'v9-practice-student', 'x', 2, 1)",
                "INSERT INTO course (id, code, name, teacher_id) "
                        + "VALUES (251, 'V9-PRACTICE', 'practice scope', 151)",
                "INSERT INTO kg_node (id, course_id, node_code, name) "
                        + "VALUES (351, 251, 'V9-PRACTICE-N', 'practice node')",
                "INSERT INTO learning_path (id, student_id, course_id, status) "
                        + "VALUES (451, 152, 251, 1)",
                "INSERT INTO learning_path_item (id, path_id, node_id, step_order) "
                        + "VALUES (551, 451, 351, 1)",
                "INSERT INTO practice_session "
                        + "(id, student_id, course_id, node_id, path_item_id, question_ids) "
                        + "VALUES (651, 153, 251, 351, 551, '1')"
        );

        Throwable failure = catchThrowable(() -> createFlyway("9").migrate());

        assertThat(failure).isNotNull();
        assertThat(failure).hasStackTraceContaining(
                "V9 precheck: practice_session.path_item scope mismatch");
        assertThat(sqlStateFromCauseChain(failure)).isEqualTo("45000");
        assertThat(countRows("practice_session", "id = 651")).isEqualTo(1);
        assertThat(columnNullable("course", "teacher_id")).isTrue();
        assertThat(constraintExists("course", "fk_course_teacher")).isFalse();
        assertThat(constraintExists("sys_user", "chk_sys_user_role")).isFalse();
        assertThat(migrationHistoryRowExists("9", true)).isFalse();
    }

    @Test
    @DisplayName("V9 遇到固定诊断作答作用域错配时在第一条持久 DDL 前以 45000 中止")
    void v9FixedDiagnosticAnswerScopeMismatch_failsBeforeFirstDdl() throws Exception {
        createFlyway("8").migrate();
        executeSql(
                "INSERT INTO sys_user (id, username, password, role, status) VALUES "
                        + "(161, 'v9-answer-teacher', 'x', 1, 1), "
                        + "(162, 'v9-session-student', 'x', 2, 1), "
                        + "(163, 'v9-answer-student', 'x', 2, 1)",
                "INSERT INTO course (id, code, name, teacher_id) "
                        + "VALUES (261, 'V9-ANSWER', 'answer scope', 161)",
                "INSERT INTO question (id, course_id, stem, stem_hash, type) "
                        + "VALUES (361, 261, 'scope question', SHA2('scope question', 256), 1)",
                "INSERT INTO diagnostic_session (id, token, student_id, course_id, status) "
                        + "VALUES (461, '00000000-0000-0000-0000-000000000461', 162, 261, 0)",
                "INSERT INTO answer_record "
                        + "(id, student_id, course_id, question_id, scene, fixed_diagnostic_session_id) "
                        + "VALUES (561, 163, 261, 361, 1, 461)"
        );

        Throwable failure = catchThrowable(() -> createFlyway("9").migrate());

        assertThat(failure).isNotNull();
        assertThat(failure).hasStackTraceContaining(
                "V9 precheck: answer_record.fixed_diagnostic_session scope mismatch");
        assertThat(sqlStateFromCauseChain(failure)).isEqualTo("45000");
        assertThat(countRows("answer_record", "id = 561")).isEqualTo(1);
        assertThat(columnNullable("course", "teacher_id")).isTrue();
        assertThat(constraintExists("course", "fk_course_teacher")).isFalse();
        assertThat(constraintExists("sys_user", "chk_sys_user_role")).isFalse();
        assertThat(migrationHistoryRowExists("9", true)).isFalse();
    }

    @Test
    @DisplayName("V9 保留 adaptive diagnostic 的多态 session_id 写入兼容性")
    void v9AllowsAdaptiveDiagnosticSessionIdsInAnswerRecord() throws Exception {
        createFlyway("8").migrate();
        executeSql(
                "INSERT INTO sys_user (id, username, password, role, status) VALUES "
                        + "(121, 'v9-teacher', 'x', 1, 1), (122, 'v9-student', 'x', 2, 1)",
                "INSERT INTO course (id, code, name, teacher_id) "
                        + "VALUES (221, 'V9-C', 'V9 course', 121)",
                "INSERT INTO question (id, course_id, stem, stem_hash, type) "
                        + "VALUES (321, 221, 'adaptive question', SHA2('adaptive question', 256), 1)",
                "INSERT INTO adaptive_diag_session (id, student_id, course_id, state_json) "
                        + "VALUES (421, 122, 221, '{}')",
                "INSERT INTO answer_record "
                        + "(id, student_id, course_id, question_id, scene, session_id) "
                        + "VALUES (521, 122, 221, 321, 1, 421)"
        );

        createFlyway("9").migrate();
        executeSql(
                "INSERT INTO answer_record "
                        + "(id, student_id, course_id, question_id, scene, session_id) "
                        + "VALUES (522, 122, 221, 321, 1, 421)"
        );

        assertThat(countRows("answer_record", "id IN (521, 522)")).isEqualTo(2);
        assertThat(constraintExists("answer_record", "fk_answer_practice_session")).isFalse();
        assertThat(constraintExists("answer_record", "fk_answer_fixed_diag")).isTrue();
    }

    private static Flyway createFlyway() {
        return createFlyway(null);
    }

    private static Flyway createFlyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("2")
                .validateOnMigrate(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static void executeSql(String... statements) throws Exception {
        try (Connection conn = MYSQL.createConnection("");
             Statement st = conn.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        }
    }

    private static int countRows(String table, String predicate) throws Exception {
        try (Connection conn = MYSQL.createConnection("");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM `" + table + "` WHERE " + predicate)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String sqlStateFromCauseChain(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                SQLException candidate = sqlException;
                while (candidate != null) {
                    if (candidate.getSQLState() != null) {
                        return candidate.getSQLState();
                    }
                    candidate = candidate.getNextException();
                }
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 加载 V1 baseline DDL，模拟「已有 m01/m02 结构、尚未跑 V3+」的遗留库。
     * <p>不用当前 {@code schema.sql}：该文件是本地最终快照，已含 V4–V17 对象与约束。
     */
    private static void applyLegacyBaselineSchema() throws Exception {
        final String raw;
        try {
            raw = new ClassPathResource("db/migration/V1__baseline_schema.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 classpath:db/migration/V1__baseline_schema.sql", e);
        }
        try (Connection conn = MYSQL.createConnection("");
             Statement st = conn.createStatement()) {
            // 按语句切分；注释中的分号极少，V1 脚本可安全按 ; 拆分
            for (String statement : raw.split(";")) {
                String sql = statement.trim();
                if (sql.isEmpty()) {
                    continue;
                }
                // 去掉纯注释块后的空语句
                String withoutLineComments = Arrays.stream(sql.split("\\R"))
                        .filter(l -> !l.trim().startsWith("--"))
                        .collect(Collectors.joining("\n"))
                        .trim();
                if (withoutLineComments.isEmpty()) {
                    continue;
                }
                st.execute(withoutLineComments);
            }
        }
    }

    private static Set<String> listBusinessTables() throws Exception {
        Set<String> tables = new TreeSet<>();
        try (Connection conn = MYSQL.createConnection("");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                             + "AND table_name <> 'flyway_schema_history'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    /**
     * 成功记录 version → checksum（baseline 的 checksum 可能为 null，仍保留 key）。
     */
    private static Map<String, Integer> successChecksums() throws Exception {
        Map<String, Integer> map = new LinkedHashMap<>();
        try (Connection conn = MYSQL.createConnection("");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT version, checksum FROM flyway_schema_history "
                             + "WHERE success = 1 ORDER BY installed_rank")) {
            while (rs.next()) {
                String version = rs.getString("version");
                if (version == null) {
                    continue;
                }
                int checksum = rs.getInt("checksum");
                map.put(version, rs.wasNull() ? null : checksum);
            }
        }
        return map;
    }

    private static int countSuccessHistoryRows() throws Exception {
        try (Connection conn = MYSQL.createConnection("");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean migrationHistoryRowExists(String version, boolean success) throws Exception {
        try (Connection conn = MYSQL.createConnection("");
             var ps = conn.prepareStatement(
                     "SELECT 1 FROM flyway_schema_history WHERE version = ? AND success = ?")) {
            ps.setString(1, version);
            ps.setBoolean(2, success);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean routineExists(String routineType, String routineName) throws Exception {
        try (Connection conn = MYSQL.createConnection("");
             var ps = conn.prepareStatement(
                     "SELECT 1 FROM information_schema.routines "
                             + "WHERE routine_schema = DATABASE() AND routine_type = ? AND routine_name = ?")) {
            ps.setString(1, routineType);
            ps.setString(2, routineName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean columnExists(String table, String column) throws Exception {
        try (Connection conn = MYSQL.createConnection("");
             var ps = conn.prepareStatement(
                     "SELECT 1 FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean columnNullable(String table, String column) throws Exception {
        try (Connection conn = MYSQL.createConnection("");
             var ps = conn.prepareStatement(
                     "SELECT IS_NULLABLE FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("missing column " + table + "." + column);
                }
                return "YES".equalsIgnoreCase(rs.getString(1));
            }
        }
    }

    private static boolean constraintExists(String table, String constraintName) throws Exception {
        try (Connection conn = MYSQL.createConnection("");
             var ps = conn.prepareStatement(
                     "SELECT 1 FROM information_schema.table_constraints "
                             + "WHERE constraint_schema = DATABASE() AND table_name = ? "
                             + "AND constraint_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Set<String> foreignKeyConstraintNames() throws Exception {
        Set<String> names = new TreeSet<>();
        try (Connection conn = MYSQL.createConnection("");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT constraint_name FROM information_schema.table_constraints "
                             + "WHERE constraint_schema = DATABASE() "
                             + "AND constraint_type = 'FOREIGN KEY'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }
}
