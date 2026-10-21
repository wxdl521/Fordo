package com.wenjin.eval.integration;

import com.wenjin.eval.fixture.EvalDatabaseGuard;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Shared Spring + MySQL base for the eval ITs (profiles {@code it,eval}; Flyway migrates the schema).
 * <ul>
 *   <li>Default: a Testcontainers {@code mysql:8.4} database named {@code wenjin_eval} (needs Docker).</li>
 *   <li>{@code -Dwenjin.eval.jdbc-url=jdbc:mysql://127.0.0.1:3306/wenjin_eval?...} (or env
 *       {@code WENJIN_EVAL_JDBC_URL}): a local database; credentials via {@code -Dwenjin.eval.jdbc-user} /
 *       {@code -Dwenjin.eval.jdbc-password} (default root/root). The demo database {@code wenjin} is refused.</li>
 * </ul>
 * The real companion AI client is enabled only for LIVE runs ({@code WENJIN_EVAL_LIVE=true} and a non-empty
 * {@code WENJIN_AI_API_KEY}); REPLAY and stub runs never reach the network.
 */
@SpringBootTest
@ActiveProfiles({"it", "eval"})
abstract class EvalDbSupport {

    private static MySQLContainer<?> container;

    static String localJdbcUrl() {
        String url = System.getProperty("wenjin.eval.jdbc-url");
        if (url == null || url.isBlank()) {
            url = System.getenv("WENJIN_EVAL_JDBC_URL");
        }
        return url == null || url.isBlank() ? null : url.trim();
    }

    static boolean liveAiAvailable() {
        String key = System.getenv("WENJIN_AI_API_KEY");
        return "true".equalsIgnoreCase(System.getenv("WENJIN_EVAL_LIVE")) && key != null && !key.isBlank();
    }

    private static synchronized MySQLContainer<?> container() {
        if (container == null) {
            container = new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("wenjin_eval")
                    .withUsername("wenjin_test")
                    .withPassword("wenjin_test");
            container.start();
        }
        return container;
    }

    /**
     * Adds the connection parameters the app relies on when absent, so a plain
     * {@code jdbc:mysql://127.0.0.1:3306/wenjin_eval} works (and no {@code &} has to survive shell quoting).
     */
    static String withRequiredParams(String url) {
        StringBuilder sb = new StringBuilder(url);
        String[][] defaults = {
                {"useAffectedRows", "true"},
                {"characterEncoding", "UTF-8"},
                {"serverTimezone", "Asia/Shanghai"},
                {"useSSL", "false"},
                {"allowPublicKeyRetrieval", "true"}};
        for (String[] kv : defaults) {
            if (!url.contains(kv[0] + "=")) {
                sb.append(sb.indexOf("?") >= 0 ? "&" : "?").append(kv[0]).append('=').append(kv[1]);
            }
        }
        return sb.toString();
    }

    @DynamicPropertySource
    static void evalDatasource(DynamicPropertyRegistry registry) {
        String local = localJdbcUrl();
        if (local != null) {
            EvalDatabaseGuard.assertSafeJdbcUrl(local);
            String url = withRequiredParams(local);
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getProperty("wenjin.eval.jdbc-user", "root"));
            registry.add("spring.datasource.password", () -> System.getProperty("wenjin.eval.jdbc-password", "root"));
        } else {
            MySQLContainer<?> mysql = container();
            registry.add("spring.datasource.url", () -> withRequiredParams(mysql.getJdbcUrl()));
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
        }
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("WENJIN_AUTH_SECRET", () -> "it-eval-secret-at-least-32-bytes-long!!");
        registry.add("wenjin.auth.secret", () -> "it-eval-secret-at-least-32-bytes-long!!");
        registry.add("wenjin.features.eval-enabled", () -> "true");
        registry.add("wenjin.ai.enabled", () -> String.valueOf(liveAiAvailable()));
        registry.add("logging.level.com.wenjin", () -> "info");
        registry.add("mybatis-plus.configuration.log-impl", () -> "org.apache.ibatis.logging.nologging.NoLoggingImpl");
    }
}
