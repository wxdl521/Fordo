package com.wenjin.eval.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.eval.fixture.EvalCourseCatalog;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.ScenarioLoader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cheap (no Spring/DB) consistency check of the classpath goldens; the real orchestration replay is
 * {@code ReplayScenarioIT}. Every scenario with a golden must parse as v2, call only known companion tools,
 * reference only fixture node codes, and have one golden turn per script line.
 */
class GoldenInventoryTest {

    static final Set<String> KNOWN_TOOLS = Set.of(
            "query_mastery", "get_node_detail", "start_practice", "trace_prerequisites",
            "get_learning_frontier", "get_review_queue", "schedule_review", "recall_student_context",
            "record_observation", "search_materials", "probe_understanding", "draft_study_plan",
            "get_plan_progress");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<EvalScenarioDocument> scenariosWithGoldens() {
        return new ScenarioLoader().loadAll().stream()
                // all ENABLED + PENDING S20/S21; S18 (PENDING memory) has no golden yet
                .filter(d -> !"S18".equals(d.code()));
    }

    @ParameterizedTest(name = "golden {0}")
    @MethodSource("scenariosWithGoldens")
    void goldenIsConsistentWithScenarioAndFixture(EvalScenarioDocument scenario) throws Exception {
        GoldenFile golden = new GoldenLoader().loadFromClasspath(scenario.code());
        assertThat(golden.scenario()).isEqualTo(scenario.code());
        assertThat(golden.format()).isEqualTo(GoldenFile.FORMAT_V2);
        assertThat(golden.turns()).as("one golden turn per script line").hasSize(scenario.script().size());
        for (GoldenFile.GoldenTurn turn : golden.turns()) {
            for (GoldenFile.GoldenRound round : turn.rounds()) {
                for (GoldenFile.GoldenToolCall call : round.toolCalls()) {
                    assertThat(KNOWN_TOOLS).as("tool name").contains(call.name());
                    JsonNode args = MAPPER.readTree(call.arguments());
                    if (args.hasNonNull("nodeCode")) {
                        assertThat(EvalCourseCatalog.nodeCodes()).contains(args.get("nodeCode").asText());
                    }
                }
            }
        }
        boolean faults = scenario.liveFaults() != null && scenario.liveFaults().llmFailures() != null;
        if (!faults) {
            assertThat(golden.roundCount()).as("non-fault scenarios replay at least one round").isPositive();
        }
    }
}
