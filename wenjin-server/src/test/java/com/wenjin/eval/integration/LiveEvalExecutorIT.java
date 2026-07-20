package com.wenjin.eval.integration;

import com.wenjin.config.FakeAiConfiguration;
import com.wenjin.eval.collect.CollectedReply;
import com.wenjin.eval.hard.EvalStepView;
import com.wenjin.eval.hard.HardAssertChecker;
import com.wenjin.eval.runner.CompanionTurnRunner;
import com.wenjin.eval.runner.EvalCaseOutcome;
import com.wenjin.eval.runner.LiveEvalExecutor;
import com.wenjin.eval.scenario.ScenarioLoader;
import com.wenjin.config.FeatureFlagsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional full-stack LIVE skeleton (Docker). Default CI uses pure unit {@code LiveEvalExecutorTest}.
 * Set {@code WENJIN_EVAL_IT=true} to enable.
 */
@SpringBootTest
@ActiveProfiles({"it", "eval"})
@Import(FakeAiConfiguration.class)
@Testcontainers
@EnabledIfEnvironmentVariable(named = "WENJIN_EVAL_IT", matches = "true")
class LiveEvalExecutorIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("wenjin")
            .withUsername("wenjin_test")
            .withPassword("wenjin_test");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?useAffectedRows=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("WENJIN_AUTH_SECRET", () -> "it-eval-secret-at-least-32-bytes-long!!");
        registry.add("wenjin.auth.secret", () -> "it-eval-secret-at-least-32-bytes-long!!");
        registry.add("wenjin.ai.enabled", () -> "false");
        registry.add("wenjin.features.eval-enabled", () -> "true");
    }

    @Autowired private FeatureFlagsProperties featureFlags;

    @Test
    @DisplayName("S09 LIVE skeleton with mocked AI client path (structure only)")
    void s09_withMockedTurns() {
        CompanionTurnRunner runner = req -> new CompanionTurnRunner.TurnResult(
                10L,
                20L,
                new CollectedReply("欢迎学习", List.of(), List.of()),
                List.of(new EvalStepView("LLM_CALL", "phase2", 0, null, "欢迎学习")));

        LiveEvalExecutor executor = new LiveEvalExecutor(
                new ScenarioLoader(),
                runner,
                new HardAssertChecker(),
                featureFlags);

        EvalCaseOutcome outcome = executor.run("S09");
        assertThat(outcome.hardPass()).isTrue();
        assertThat(outcome.scenarioCode()).isEqualTo("S09");
    }
}
