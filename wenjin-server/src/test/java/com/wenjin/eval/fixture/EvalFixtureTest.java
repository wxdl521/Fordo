package com.wenjin.eval.fixture;

import com.wenjin.eval.scenario.EvalScenarioDocument.MasteryOverride;
import com.wenjin.eval.scenario.EvalScenarioDocument.MistakeSeed;
import com.wenjin.eval.scenario.EvalScenarioDocument.Persona;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Persona injection assertions without Docker (in-memory ports).
 */
class EvalFixtureTest {

    private EvalFixture fixture;
    private InMemoryEvalMasteryPort masteryPort;
    private InMemoryEvalReviewQueuePort reviewPort;
    private InMemoryEvalSessionPort sessionPort;

    @BeforeEach
    void setUp() {
        EvalWorld world = new EvalWorld(1L, 2L, 3L, "EVAL_COURSE",
                java.util.Set.of("SE-3.2", "SE-2.1", "KT01"));
        masteryPort = new InMemoryEvalMasteryPort();
        reviewPort = new InMemoryEvalReviewQueuePort();
        sessionPort = new InMemoryEvalSessionPort();
        MapEvalNodeLookup nodes = new MapEvalNodeLookup(3L, Map.of(
                "SE-3.2", 10L,
                "SE-2.1", 11L,
                "KT01", 12L));
        fixture = new EvalFixture(world, masteryPort, reviewPort, sessionPort, nodes);
    }

    @Test
    @DisplayName("applyPersona: masteryOverrides 经证据写路径灌入 raw/effective")
    void applyPersona_injectsMasteryViaWritePath() {
        Persona persona = new Persona(
                List.of(new MasteryOverride("SE-3.2", 0.35, 0.30)),
                List.of(),
                "weak on 3.2");

        EvalFixture.PersonaInjectionResult result = fixture.applyPersona(persona);

        assertThat(result.appliedMasteryCodes()).containsExactly("SE-3.2");
        assertThat(result.missingNodeCodes()).isEmpty();
        assertThat(result.rawByCode().get("SE-3.2")).isCloseTo(0.35, within(0.001));
        // effective lower than raw after backdated decay
        assertThat(result.effectiveByCode().get("SE-3.2"))
                .isLessThan(result.rawByCode().get("SE-3.2"));
        assertThat(result.effectiveByCode().get("SE-3.2"))
                .isCloseTo(0.30, within(0.03));
    }

    @Test
    @DisplayName("applyPersona: mistakes 经错题端口写入（靠前者更近），未知节点记入 missing")
    void applyPersona_seedsMistakes() {
        InMemoryEvalMistakePort mistakePort = new InMemoryEvalMistakePort();
        EvalFixture withMistakes = new EvalFixture(
                new EvalWorld(1L, 2L, 3L, "EVAL_COURSE", java.util.Set.of("SE-3.2", "SE-2.1", "KT01")),
                masteryPort, reviewPort, sessionPort,
                new MapEvalNodeLookup(3L, Map.of("SE-3.2", 10L, "SE-2.1", 11L, "KT01", 12L)),
                mistakePort);

        EvalFixture.PersonaInjectionResult result = withMistakes.applyPersona(new Persona(
                List.of(), List.of(), "mistakes",
                List.of(new MistakeSeed("SE-3.2", "SE-2.1"),
                        new MistakeSeed("KT01", "SE-2.1"),
                        new MistakeSeed("NOPE", "SE-2.1"))));

        assertThat(result.appliedMistakeCodes()).containsExactly("SE-3.2", "KT01");
        assertThat(result.missingNodeCodes()).containsExactly("NOPE");
        assertThat(mistakePort.seeds()).extracting(InMemoryEvalMistakePort.Seed::nodeId).containsExactly(10L, 12L);
        assertThat(mistakePort.seeds().get(0).misconceptionNodeCode()).isEqualTo("SE-2.1");
        assertThat(mistakePort.seeds().get(0).minutesAgo()).isLessThan(mistakePort.seeds().get(1).minutesAgo());
        assertThat(mistakePort.seeds()).allMatch(s -> s.studentId() == 2L && s.courseId() == 3L);
    }

    @Test
    @DisplayName("applyPersona: reviewQueue 走 pin 路径进入 forcedToday")
    void applyPersona_pinsReviewQueue() {
        Persona persona = new Persona(
                List.of(),
                List.of("SE-2.1", "KT01"),
                "queue");

        EvalFixture.PersonaInjectionResult result = fixture.applyPersona(persona);

        assertThat(result.appliedReviewCodes()).containsExactly("SE-2.1", "KT01");
        assertThat(result.reviewPins()).containsKeys("SE-2.1", "KT01");
        assertThat(reviewPort.forcedTodayCodes(2L, 3L)).containsExactlyInAnyOrder("SE-2.1", "KT01");
    }

    @Test
    @DisplayName("resetStudentState: 清空掌握度、队列与会话计数")
    void resetStudentState_clearsMutableState() {
        fixture.applyPersona(new Persona(
                List.of(new MasteryOverride("SE-3.2", 0.5, null)),
                List.of("SE-2.1"),
                null));

        fixture.resetStudentState();

        assertThat(masteryPort.readRaw01(2L, 3L, 10L)).isNull();
        assertThat(reviewPort.forcedToday(2L, 3L)).isEmpty();
        assertThat(sessionPort.clearCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("prepareScenario: 先 reset 再注入，缺失节点记入 missing")
    void prepareScenario_reportsMissingNodes() {
        // inject something first
        fixture.applyPersona(new Persona(
                List.of(new MasteryOverride("KT01", 0.9, null)),
                List.of(),
                null));

        Persona persona = new Persona(
                List.of(
                        new MasteryOverride("SE-3.2", 0.35, 0.30),
                        new MasteryOverride("NOPE-9", 0.1, null)),
                List.of("GHOST"),
                "note");

        EvalFixture.PersonaInjectionResult result = fixture.prepareScenario(
                new com.wenjin.eval.scenario.EvalScenarioDocument(
                        "S01",
                        "t",
                        persona,
                        List.of(new com.wenjin.eval.scenario.EvalScenarioDocument.ScriptTurn("hi")),
                        new com.wenjin.eval.scenario.EvalScenarioDocument.HardAssertions(
                                List.of(), List.of(), 4, true, true, false),
                        new com.wenjin.eval.scenario.EvalScenarioDocument.JudgeSpec(List.of(
                                new com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension(
                                        "expression", "e", 3))),
                        "ENABLED",
                        null));

        // KT01 cleared by reset
        assertThat(masteryPort.readRaw01(2L, 3L, 12L)).isNull();
        assertThat(result.appliedMasteryCodes()).containsExactly("SE-3.2");
        assertThat(result.missingNodeCodes()).containsExactlyInAnyOrder("NOPE-9", "GHOST");
        assertThat(sessionPort.clearCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("daysForTargetEffective: 衰减反解在合理区间")
    void daysForTargetEffective_roundTrip() {
        double days = InMemoryEvalMasteryPort.daysForTargetEffective(35.0, 30.0);
        assertThat(days).isBetween(1.0, 100.0);
        double effective = com.wenjin.service.MasteryDecayCalculator.effectiveScore(
                35.0, days, 30.0, 25.0, 1);
        assertThat(effective).isCloseTo(30.0, within(0.5));
    }
}
