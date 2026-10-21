package com.wenjin.eval.fixture;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Locale;
import java.util.Set;

/**
 * Hard guard: the eval harness resets student state and seeds a course, so it must never touch the demo
 * database {@code wenjin}. Use a dedicated database (default {@code wenjin_eval}) or Testcontainers.
 */
public final class EvalDatabaseGuard {

    public static final Set<String> FORBIDDEN = Set.of("wenjin");

    private EvalDatabaseGuard() {
    }

    /** Database name of a MySQL JDBC URL ({@code jdbc:mysql://host:port/NAME?params}), or null. */
    public static String databaseName(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        String url = jdbcUrl.trim();
        int q = url.indexOf('?');
        if (q >= 0) {
            url = url.substring(0, q);
        }
        int scheme = url.indexOf("//");
        if (scheme < 0) {
            return null;
        }
        int slash = url.indexOf('/', scheme + 2);
        if (slash < 0 || slash == url.length() - 1) {
            return null;
        }
        return url.substring(slash + 1);
    }

    /** Throws when the URL targets a forbidden (demo) database or names no database. */
    public static void assertSafeJdbcUrl(String jdbcUrl) {
        String db = databaseName(jdbcUrl);
        if (db == null || db.isBlank()) {
            throw new IllegalStateException("eval JDBC URL must name a database (e.g. wenjin_eval): " + jdbcUrl);
        }
        assertSafeName(db);
    }

    /** Throws when the connection's current database is forbidden. */
    public static void assertSafe(JdbcTemplate jdbc) {
        String db = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertSafeName(db);
    }

    private static void assertSafeName(String db) {
        if (db == null || FORBIDDEN.contains(db.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("Refusing to run eval against database '" + db
                    + "': it is the demo database. Use a dedicated database such as wenjin_eval.");
        }
    }
}
