package com.wenjin.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

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
 *   <li>空库：执行 V1…V9 建出全部业务表与后续增量，二次 migrate 无变更且 checksum 稳定</li>
 *   <li>遗留库：先加载 V1 级结构（m01/m02），baseline version 2 接管，执行 V3+（含 V4–V9）</li>
 * </ul>
 * <p>说明：本地 {@code schema.sql} 是「当前最终 DDL」快照（已含 V4–V9 对象与物理 FK），不能直接当作
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

    /** 空库跑完 V1…V9 后应有的业务表（V4–V6/V8–V9 为 ALTER；V7 增 ai_job）。 */
    private static final List<String> EXPECTED_TABLES_AFTER_ALL;

    /** V9 合同迁移新增的命名外键（抽样 + 关键约束）。 */
    private static final List<String> V9_EXPECTED_FKS = List.of(
            "fk_course_teacher",
            "fk_node_course",
            "fk_edge_from_node",
            "fk_question_course",
            "fk_qn_node",
            "fk_mastery_node",
            "fk_path_item_path",
            "fk_practice_node",
            "fk_answer_question",
            "fk_sc_student",
            "fk_companion_msg_conv",
            "fk_ai_job_course",
            "fk_diag_course",
            "chk_sys_user_role"
    );

    static {
        List<String> all = new ArrayList<>(BASELINE_TABLES);
        all.addAll(V3_TABLES);
        all.addAll(V7_TABLES);
        EXPECTED_TABLES_AFTER_ALL = List.copyOf(all);
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
            for (String table : tables) {
                st.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    @Test
    @DisplayName("空库：V1…V9 建出全部表；二次 migrate 无变更且 checksum 稳定；V9 约束就绪")
    void emptyDatabase_appliesV1ThroughV9_idempotent() throws Exception {
        Flyway flyway = createFlyway();

        MigrateResult first = flyway.migrate();
        assertThat(first.success).isTrue();
        assertThat(first.migrationsExecuted).isEqualTo(9);

        assertThat(listBusinessTables()).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES_AFTER_ALL);
        Map<String, Integer> checksumsAfterFirst = successChecksums();
        assertThat(checksumsAfterFirst.keySet()).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(checksumsAfterFirst.get("1")).isNotNull();
        assertThat(checksumsAfterFirst.get("2")).isNotNull();
        assertThat(checksumsAfterFirst.get("3")).isNotNull();
        assertThat(checksumsAfterFirst.get("4")).isNotNull();
        assertThat(checksumsAfterFirst.get("5")).isNotNull();
        assertThat(checksumsAfterFirst.get("6")).isNotNull();
        assertThat(checksumsAfterFirst.get("7")).isNotNull();
        assertThat(checksumsAfterFirst.get("8")).isNotNull();
        assertThat(checksumsAfterFirst.get("9")).isNotNull();
        assertThat(columnExists("companion_conversation", "summary_text")).isTrue();
        assertThat(columnExists("companion_conversation", "summary_through_message_id")).isTrue();
        assertThat(columnNullable("course", "teacher_id")).isFalse();
        assertThat(columnNullable("question", "stem_hash")).isFalse();
        assertThat(constraintExists("sys_user", "chk_sys_user_role")).isTrue();
        assertThat(constraintExists("course", "fk_course_teacher")).isTrue();
        assertThat(constraintExists("kg_node", "fk_node_course")).isTrue();
        for (String name : V9_EXPECTED_FKS) {
            assertThat(constraintNameExists(name))
                    .as("expected constraint %s after V9", name)
                    .isTrue();
        }

        MigrateResult second = flyway.migrate();
        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isEqualTo(0);

        Map<String, Integer> checksumsAfterSecond = successChecksums();
        assertThat(checksumsAfterSecond).isEqualTo(checksumsAfterFirst);
        assertThat(countSuccessHistoryRows()).isEqualTo(9);
    }

    @Test
    @DisplayName("遗留库：V1 结构 + baseline@2 接管，执行 V3…V9；二次 migrate 无变更")
    void legacySchema_baselinesAtVersion2_onlyFutureMigrations() throws Exception {
        applyLegacyBaselineSchema();
        assertThat(listBusinessTables()).containsExactlyInAnyOrderElementsOf(BASELINE_TABLES);

        Flyway flyway = createFlyway();
        MigrateResult first = flyway.migrate();
        assertThat(first.success).isTrue();
        // baseline@2 后执行 V3、V4、V5、V6、V7、V8、V9
        assertThat(first.migrationsExecuted).isEqualTo(7);

        Map<String, Integer> history = successChecksums();
        // baseline 记录 version=2；不得出现 version=1 的 SUCCESS 迁移；应有 V3–V9
        assertThat(history.keySet()).doesNotContain("1");
        assertThat(history.keySet()).contains("2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(listBusinessTables()).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES_AFTER_ALL);
        assertThat(columnExists("companion_conversation", "summary_text")).isTrue();
        assertThat(constraintExists("course", "fk_course_teacher")).isTrue();
        assertThat(columnNullable("course", "teacher_id")).isFalse();

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
     * <p>不用当前 {@code schema.sql}：该文件是本地最终快照，已含 V4–V9 对象与约束。
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
                if (sql.isEmpty() || sql.startsWith("--")) {
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

    private static boolean constraintNameExists(String constraintName) throws Exception {
        try (Connection conn = MYSQL.createConnection("");
             var ps = conn.prepareStatement(
                     "SELECT 1 FROM information_schema.table_constraints "
                             + "WHERE constraint_schema = DATABASE() AND constraint_name = ?")) {
            ps.setString(1, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
