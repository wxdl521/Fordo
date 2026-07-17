package com.wenjin.integration;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.ai.GraphCleanAiClient;
import com.wenjin.ai.ImageToMarkdownAiClient;
import com.wenjin.ai.QuestionAiClient;
import com.wenjin.ai.QuestionBankCleanAiClient;
import com.wenjin.ai.SyllabusGraphAiClient;
import com.wenjin.config.FakeAiConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全量应用上下文冒烟：Testcontainers MySQL + 随机端口 + Fake AI，禁止真实 API Key / 出网。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Import(FakeAiConfiguration.class)
class ApplicationContextIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("WENJIN_AUTH_SECRET", () -> "it-test-secret-at-least-32-bytes-long");
        registry.add("wenjin.auth.secret", () -> "it-test-secret-at-least-32-bytes-long");
        // 显式清空真实 Key；Fake AI @Primary 覆盖出网客户端
        registry.add("wenjin.ai.enabled", () -> "false");
        registry.add("wenjin.ai.api-key", () -> "");
        registry.add("WENJIN_AI_API_KEY", () -> "");
        registry.add("wenjin.ai.vision.api-key", () -> "");
        registry.add("WENJIN_AI_VISION_API_KEY", () -> "");
        registry.add("wenjin.jobs.poll-interval-ms", () -> "3600000");
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private CompanionAiClient companionAiClient;

    @Autowired
    private QuestionAiClient questionAiClient;

    @Autowired
    private GraphCleanAiClient graphCleanAiClient;

    @Autowired
    private QuestionBankCleanAiClient questionBankCleanAiClient;

    @Autowired
    private ImageToMarkdownAiClient imageToMarkdownAiClient;

    @Autowired
    private SyllabusGraphAiClient syllabusGraphAiClient;

    @Test
    @DisplayName("应用上下文可在 Testcontainers MySQL 与 Fake AI 下完整启动")
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
        assertThat(environment.getActiveProfiles()).contains("it");
        assertThat(environment.getProperty("wenjin.ai.api-key", "")).isEmpty();
        assertThat(environment.getProperty("wenjin.ai.vision.api-key", "")).isEmpty();

        // Fake beans win via @Primary (class names from FakeAiConfiguration lambdas / anon)
        assertThat(companionAiClient).isNotNull();
        assertThat(questionAiClient).isNotNull();
        assertThat(graphCleanAiClient).isNotNull();
        assertThat(questionBankCleanAiClient).isNotNull();
        assertThat(imageToMarkdownAiClient).isNotNull();
        assertThat(syllabusGraphAiClient).isNotNull();

        assertThat(companionAiClient.getClass().getName())
                .doesNotContain("OpenAiCompatible");
        assertThat(questionAiClient.getClass().getName())
                .doesNotContain("OpenAiCompatible");
    }
}
