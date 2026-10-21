package com.wenjin.eval.runner;

import com.wenjin.config.AiProperties;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.eval.fixture.EvalFixture;
import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.golden.GoldenLoader;
import com.wenjin.eval.hard.HardAssertChecker;
import com.wenjin.eval.judge.JudgeAiClient;
import com.wenjin.eval.judge.JudgeService;
import com.wenjin.eval.metrics.EvalProperties;
import com.wenjin.eval.scenario.ScenarioLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Spring-wired entry point of the eval harness ({@code eval} profile): the fixture world, the real turn
 * runner, the switchable AI client and factory methods for REPLAY / LIVE executors and the LIVE suite.
 */
public final class EvalHarness {

    private final Supplier<EvalFixture> fixture;
    private final CompanionTurnRunner turnRunner;
    private final EvalCompanionAiClient aiClient;
    private final CompanionToolProperties toolProperties;
    private final FeatureFlagsProperties featureFlags;
    private final AiProperties aiProperties; // nullable
    private final EvalProperties evalProperties; // nullable
    private final JudgeAiClient judgeAiClient; // nullable
    private final ScenarioLoader scenarioLoader = new ScenarioLoader();
    private final GoldenLoader goldenLoader = new GoldenLoader();
    private final HardAssertChecker checker = new HardAssertChecker();

    public EvalHarness(Supplier<EvalFixture> fixture,
                       CompanionTurnRunner turnRunner,
                       EvalCompanionAiClient aiClient,
                       CompanionToolProperties toolProperties,
                       FeatureFlagsProperties featureFlags,
                       AiProperties aiProperties,
                       EvalProperties evalProperties,
                       JudgeAiClient judgeAiClient) {
        this.fixture = Objects.requireNonNull(fixture, "fixture");
        this.turnRunner = Objects.requireNonNull(turnRunner, "turnRunner");
        this.aiClient = Objects.requireNonNull(aiClient, "aiClient");
        this.toolProperties = toolProperties;
        this.featureFlags = featureFlags;
        this.aiProperties = aiProperties;
        this.evalProperties = evalProperties;
        this.judgeAiClient = judgeAiClient;
    }

    public EvalFixture fixture() {
        return fixture.get();
    }

    public EvalCompanionAiClient aiClient() {
        return aiClient;
    }

    public ScenarioLoader scenarioLoader() {
        return scenarioLoader;
    }

    public GoldenLoader goldenLoader() {
        return goldenLoader;
    }

    /** REPLAY executor over classpath goldens ({@code eval/golden/S0X.json}). */
    public ReplayEvalExecutor replayExecutor() {
        return replayExecutor(goldenLoader::loadFromClasspath);
    }

    /** REPLAY executor over a custom golden source (e.g. a modified golden in a test). */
    public ReplayEvalExecutor replayExecutor(Function<String, GoldenFile> goldenSource) {
        EvalScenarioEnvironment env = EvalScenarioEnvironment.replay(aiClient, toolProperties, goldenSource);
        return new ReplayEvalExecutor(scenarioLoader, turnRunner, checker, featureFlags, fixture(), goldenLoader, env);
    }

    /** LIVE executor over the base client; {@code record} non-null enables RECORD. */
    public LiveEvalExecutor liveExecutor(EvalScenarioEnvironment.RecordSettings record) {
        return liveExecutor(EvalScenarioEnvironment.live(aiClient, toolProperties, record));
    }

    public LiveEvalExecutor liveExecutor(EvalScenarioEnvironment environment) {
        return new LiveEvalExecutor(scenarioLoader, turnRunner, checker, featureFlags, fixture(), environment);
    }

    /** Suite over the given executor function (LIVE or REPLAY). */
    public LiveEvalSuite suite(Function<com.wenjin.eval.scenario.EvalScenarioDocument, ScenarioRun> executor) {
        return new LiveEvalSuite(scenarioLoader, executor,
                evalProperties == null ? new EvalProperties.Price() : evalProperties.getPrice());
    }

    /** Judge service over the configured judge client ({@code wenjin.eval.judge-model}); null when unavailable. */
    public JudgeService judgeService() {
        return judgeAiClient == null ? null : new JudgeService(judgeAiClient);
    }

    /** Model name recorded into goldens. */
    public String modelName() {
        return aiProperties == null || aiProperties.getModel() == null ? "" : aiProperties.getModel();
    }

    /** Convenience: run the LIVE suite. */
    public LiveEvalSuite.Result runLive(List<String> codes, int repeat, boolean judge,
                                        EvalScenarioEnvironment.RecordSettings record, Path outBaseDir) {
        LiveEvalExecutor executor = liveExecutor(record);
        return suite(executor::runDetailed)
                .run(new LiveEvalSuite.Options(codes, repeat, judge ? judgeService() : null, outBaseDir));
    }
}
