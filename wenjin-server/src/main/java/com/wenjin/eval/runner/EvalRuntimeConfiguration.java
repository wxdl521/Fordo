package com.wenjin.eval.runner;

import com.wenjin.ai.OpenAiCompatibleCompanionAiClient;
import com.wenjin.config.AiProperties;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.eval.collect.AgentTraceReader;
import com.wenjin.eval.fixture.DbEvalMasteryPort;
import com.wenjin.eval.fixture.EvalFixture;
import com.wenjin.eval.fixture.EvalWorldBuilder;
import com.wenjin.eval.fixture.JdbcEvalMistakePort;
import com.wenjin.eval.fixture.JdbcEvalRosterPort;
import com.wenjin.eval.fixture.JdbcEvalSessionPort;
import com.wenjin.eval.fixture.MapperEvalNodeLookup;
import com.wenjin.eval.fixture.StoreEvalReviewQueuePort;
import com.wenjin.eval.judge.JudgeAiClient;
import com.wenjin.eval.metrics.EvalProperties;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.MasterySnapshotMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.CompanionService;
import com.wenjin.service.assistant.TeacherAssistantService;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.MasteryService;
import com.wenjin.service.companion.ReviewScheduleStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Eval harness wiring ({@code eval} profile only — never active in production): the companion service is
 * given a switchable {@link EvalCompanionAiClient} (REPLAY / LIVE / RECORD / fault injection), and the
 * fixture, turn runner and executors are exposed through {@link EvalHarness}. DB-touching beans are lazy so
 * they are created after Flyway has migrated the eval database.
 */
@Configuration
@Profile("eval")
@ConditionalOnProperty(prefix = "wenjin.features", name = "eval-enabled", havingValue = "true", matchIfMissing = true)
public class EvalRuntimeConfiguration {

    @Bean
    @Primary
    public EvalCompanionAiClient evalCompanionAiClient(OpenAiCompatibleCompanionAiClient real) {
        return new EvalCompanionAiClient(real);
    }

    @Bean
    @Lazy
    public EvalWorldBuilder evalWorldBuilder(JdbcTemplate jdbc) {
        return new EvalWorldBuilder(jdbc);
    }

    @Bean
    @Lazy
    public EvalFixture evalFixture(EvalWorldBuilder worldBuilder,
                                   StudentMasteryMapper masteryMapper,
                                   MasterySnapshotMapper snapshotMapper,
                                   MasteryService masteryService,
                                   MasteryDecayCalculator decayCalculator,
                                   ReviewScheduleStore scheduleStore,
                                   KgNodeMapper nodeMapper,
                                   JdbcTemplate jdbc) {
        return new EvalFixture(
                worldBuilder.ensureWorld(),
                new DbEvalMasteryPort(masteryMapper, snapshotMapper, masteryService, decayCalculator, jdbc),
                new StoreEvalReviewQueuePort(scheduleStore),
                new JdbcEvalSessionPort(jdbc),
                new MapperEvalNodeLookup(nodeMapper),
                new JdbcEvalMistakePort(jdbc),
                new JdbcEvalRosterPort(jdbc));
    }

    @Bean
    @Lazy
    public ServiceCompanionTurnRunner serviceCompanionTurnRunner(CompanionService companionService,
                                                                 JdbcTemplate jdbc,
                                                                 EvalCompanionAiClient aiClient) {
        return new ServiceCompanionTurnRunner(companionService, new AgentTraceReader(jdbc), aiClient);
    }

    @Bean
    @Lazy
    public TeacherAssistantTurnRunner teacherAssistantTurnRunner(TeacherAssistantService assistantService,
                                                                 JdbcTemplate jdbc,
                                                                 EvalCompanionAiClient aiClient) {
        return new TeacherAssistantTurnRunner(assistantService, new AgentTraceReader(jdbc), aiClient);
    }

    @Bean
    @Lazy
    public EvalHarness evalHarness(ObjectProvider<EvalFixture> fixture,
                                   ServiceCompanionTurnRunner studentRunner,
                                   TeacherAssistantTurnRunner teacherRunner,
                                   EvalCompanionAiClient aiClient,
                                   ObjectProvider<CompanionToolProperties> toolProperties,
                                   ObjectProvider<FeatureFlagsProperties> featureFlags,
                                   ObjectProvider<AiProperties> aiProperties,
                                   ObjectProvider<EvalProperties> evalProperties,
                                   ObjectProvider<JudgeAiClient> judgeAiClient) {
        return new EvalHarness(fixture::getObject, new ActorRoutingTurnRunner(studentRunner, teacherRunner), aiClient,
                toolProperties.getIfAvailable(), featureFlags.getIfAvailable(), aiProperties.getIfAvailable(),
                evalProperties.getIfAvailable(), judgeAiClient.getIfAvailable());
    }
}
