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

/**
 * 受控迁移两条路径：
 * <ul>
 *   <li>空库：执行 V1+V2+V3 建出全部业务表，二次 migrate 无变更且 checksum 稳定</li>
 *   <li>遗留库：先加载当前 {@code schema.sql}，baseline version 2 接管，仅执行 V3+</li>
 * </ul>
 */
class SchemaMigrationIT extends MySqlContainerSupport {

    /** V1/schema.sql 业务表 + V3 固定诊断会话表（不含 flyway_schema_history）。 */
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

    private static final List<String> EXPECTED_TABLES_AFTER_V3;

    static {
        List<String> all = new ArrayList<>(BASELINE_TABLES);
        all.addAll(V3_TABLES);
        EXPECTED_TABLES_AFTER_V3 = List.copyOf(all);
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
    @DisplayName("空库：V1+V2+V3 建出全部表；二次 migrate 无变更且 checksum 稳定")
    void emptyDatabase_appliesV1AndV2_idempotent() throws Exception {
        Flyway flyway = createFlyway();

        MigrateResult first = flyway.migrate();
        assertThat(first.success).isTrue();
        assertThat(first.migrationsExecuted).isEqualTo(3);

        assertThat(listBusinessTables()).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES_AFTER_V3);
        Map<String, Integer> checksumsAfterFirst = successChecksums();
        assertThat(checksumsAfterFirst.keySet()).containsExactly("1", "2", "3");
        assertThat(checksumsAfterFirst.get("1")).isNotNull();
        assertThat(checksumsAfterFirst.get("2")).isNotNull();
        assertThat(checksumsAfterFirst.get("3")).isNotNull();

        MigrateResult second = flyway.migrate();
        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isEqualTo(0);

        Map<String, Integer> checksumsAfterSecond = successChecksums();
        assertThat(checksumsAfterSecond).isEqualTo(checksumsAfterFirst);
        assertThat(countSuccessHistoryRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("遗留库：schema.sql 后 baseline@2 接管，仅执行 V3；二次 migrate 无变更")
    void legacySchema_baselinesAtVersion2_onlyFutureMigrations() throws Exception {
        applyLegacySchemaSql();
        assertThat(listBusinessTables()).containsExactlyInAnyOrderElementsOf(BASELINE_TABLES);

        Flyway flyway = createFlyway();
        MigrateResult first = flyway.migrate();
        assertThat(first.success).isTrue();
        // baseline@2 后执行 V3
        assertThat(first.migrationsExecuted).isEqualTo(1);

        Map<String, Integer> history = successChecksums();
        // baseline 记录 version=2；不得出现 version=1 的 SUCCESS 迁移；应有 V3
        assertThat(history.keySet()).doesNotContain("1");
        assertThat(history.keySet()).contains("2", "3");
        assertThat(listBusinessTables()).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES_AFTER_V3);

        MigrateResult second = flyway.migrate();
        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isEqualTo(0);
        assertThat(successChecksums()).isEqualTo(history);
    }

    private static Flyway createFlyway() {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("2")
                .validateOnMigrate(true)
                .load();
    }

    /**
     * 加载当前本地快照 schema.sql，模拟「已有 m01/m02 结构」的遗留库。
     * 跳过 CREATE DATABASE / USE（容器库已存在，测试账号通常无全局建库权）。
     */
    private static void applyLegacySchemaSql() throws Exception {
        final String raw;
        try {
            raw = new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 classpath:schema.sql", e);
        }
        String filtered = Arrays.stream(raw.split("\\R"))
                .filter(line -> !line.matches("(?i)\\s*CREATE\\s+DATABASE\\b.*"))
                .filter(line -> !line.matches("(?i)\\s*USE\\b.*"))
                .collect(Collectors.joining("\n"));
        try (Connection conn = MYSQL.createConnection("");
             Statement st = conn.createStatement()) {
            // 按语句切分；注释中的分号极少，schema 快照可安全按 ; 拆分
            for (String statement : filtered.split(";")) {
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
}
