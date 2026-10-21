package com.wenjin.eval.fixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalDatabaseGuardTest {

    @Test
    @DisplayName("database name parsed from MySQL JDBC URL")
    void databaseName() {
        assertThat(EvalDatabaseGuard.databaseName(
                "jdbc:mysql://127.0.0.1:3306/wenjin_eval?characterEncoding=UTF-8")).isEqualTo("wenjin_eval");
        assertThat(EvalDatabaseGuard.databaseName("jdbc:mysql://h/wenjin")).isEqualTo("wenjin");
        assertThat(EvalDatabaseGuard.databaseName("jdbc:mysql://h:3306/")).isNull();
    }

    @Test
    @DisplayName("refuses the demo database wenjin (any case) and URLs without a database")
    void refusesDemoDatabase() {
        EvalDatabaseGuard.assertSafeJdbcUrl("jdbc:mysql://127.0.0.1:3306/wenjin_eval?useSSL=false");
        assertThatThrownBy(() -> EvalDatabaseGuard.assertSafeJdbcUrl("jdbc:mysql://127.0.0.1:3306/wenjin?x=1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("demo database");
        assertThatThrownBy(() -> EvalDatabaseGuard.assertSafeJdbcUrl("jdbc:mysql://h/WENJIN"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EvalDatabaseGuard.assertSafeJdbcUrl("jdbc:mysql://h:3306"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("catalog covers every node code referenced by scenarios")
    void catalogCoversScenarioCodes() {
        assertThat(EvalCourseCatalog.nodeCodes())
                .contains("SE-1.1", "SE-2.1", "SE-2.2", "SE-3.1", "SE-3.2", "SE-4.1");
        for (EvalCourseCatalog.Edge e : EvalCourseCatalog.EDGES) {
            assertThat(EvalCourseCatalog.nodeCodes()).contains(e.from(), e.to());
        }
    }
}
