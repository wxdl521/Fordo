package com.wenjin.eval.runner;

import com.wenjin.config.CompanionToolProperties;
import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.golden.GoldenRecorder;
import com.wenjin.eval.golden.RecordingCompanionAiClient;
import com.wenjin.eval.hard.HardAssertFailure;
import com.wenjin.eval.replay.ReplayCompanionAiClient;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.EvalScenarioDocument.LiveFaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@link ScenarioLifecycle} used by the Spring-wired harness:
 * <ul>
 *   <li>REPLAY: installs a fresh {@link ReplayCompanionAiClient} for the scenario golden; after the run,
 *       unconsumed / exhausted rounds become {@code replay} hard failures ("need re-record");</li>
 *   <li>LIVE: uses the base client, optionally wrapped in a recorder (RECORD) that writes a golden v2 file;</li>
 *   <li>both: {@code liveFaults.llmFailures} (fault layer) and {@code liveFaults.maxToolCalls} (temporary
 *       override of {@code wenjin.companion.tools.max-tool-calls}, restored afterwards).</li>
 * </ul>
 * RECORD keeps the first hard-passing repetition of a scenario; until one passes, the latest run is kept.
 */
public final class EvalScenarioEnvironment implements ScenarioLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EvalScenarioEnvironment.class);

    public static final String REPLAY_CHECK = "replay";

    /** RECORD settings; {@code dir} receives {@code S0X.json}. */
    public record RecordSettings(Path dir, String model) {
        public RecordSettings {
            Objects.requireNonNull(dir, "dir");
        }
    }

    private final EvalMode mode;
    private final EvalCompanionAiClient client;
    private final CompanionToolProperties toolProperties; // nullable
    private final Function<String, GoldenFile> goldenSource; // REPLAY only
    private final RecordSettings record; // nullable = no recording
    private final GoldenRecorder recorder = new GoldenRecorder();
    private final Map<String, Boolean> recordedPassing = new LinkedHashMap<>();
    private final List<Path> recordedFiles = new ArrayList<>();

    private ReplayCompanionAiClient replay;
    private Integer savedMaxToolCalls;

    private EvalScenarioEnvironment(EvalMode mode,
                                    EvalCompanionAiClient client,
                                    CompanionToolProperties toolProperties,
                                    Function<String, GoldenFile> goldenSource,
                                    RecordSettings record) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.client = Objects.requireNonNull(client, "client");
        this.toolProperties = toolProperties;
        this.goldenSource = goldenSource;
        this.record = record;
    }

    public static EvalScenarioEnvironment replay(EvalCompanionAiClient client,
                                                 CompanionToolProperties toolProperties,
                                                 Function<String, GoldenFile> goldenSource) {
        return new EvalScenarioEnvironment(EvalMode.REPLAY, client, toolProperties,
                Objects.requireNonNull(goldenSource, "goldenSource"), null);
    }

    public static EvalScenarioEnvironment live(EvalCompanionAiClient client,
                                               CompanionToolProperties toolProperties,
                                               RecordSettings record) {
        return new EvalScenarioEnvironment(EvalMode.LIVE, client, toolProperties, null, record);
    }

    /** Golden files written by RECORD so far. */
    public synchronized List<Path> recordedFiles() {
        return List.copyOf(recordedFiles);
    }

    @Override
    public synchronized void beforeScenario(EvalScenarioDocument scenario) {
        LiveFaults faults = scenario.liveFaults();
        replay = null;
        if (toolProperties != null && faults != null && faults.maxToolCalls() != null) {
            savedMaxToolCalls = toolProperties.getMaxToolCalls();
            toolProperties.setMaxToolCalls(faults.maxToolCalls());
        }
        if (mode == EvalMode.REPLAY) {
            replay = new ReplayCompanionAiClient(goldenSource.apply(scenario.code()));
            client.configureScenario(replay, faults, false);
        } else {
            client.configureScenario(null, faults, record != null);
        }
    }

    @Override
    public synchronized EvalCaseOutcome afterScenario(EvalScenarioDocument scenario, EvalCaseOutcome outcome) {
        try {
            if (replay != null) {
                List<HardAssertFailure> problems = replay.verifyFullyConsumed().stream()
                        .map(p -> new HardAssertFailure(REPLAY_CHECK, p))
                        .toList();
                outcome = ScenarioDriver.withExtraFailures(outcome, problems);
            }
            RecordingCompanionAiClient rec = client.recorder();
            if (record != null && rec != null && !outcome.skipped()) {
                writeGolden(scenario.code(), rec, outcome.hardPass());
            }
            return outcome;
        } finally {
            if (savedMaxToolCalls != null && toolProperties != null) {
                toolProperties.setMaxToolCalls(savedMaxToolCalls);
            }
            savedMaxToolCalls = null;
            replay = null;
            client.clearScenario();
        }
    }

    private void writeGolden(String code, RecordingCompanionAiClient rec, boolean passed) {
        if (Boolean.TRUE.equals(recordedPassing.get(code))) {
            return; // a passing repetition is already recorded
        }
        GoldenFile golden = recorder.build(code, record.model(), Instant.now(), false, rec.recordedTurns());
        Path path = recorder.write(golden, GoldenRecorder.defaultGoldenPath(record.dir(), code));
        recordedPassing.put(code, passed);
        if (!recordedFiles.contains(path)) {
            recordedFiles.add(path);
        }
        log.info("RECORD {} → {} (hardPass={})", code, path, passed);
    }
}
