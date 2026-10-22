package com.wenjin.eval.fixture;

import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.EvalScenarioDocument.HardAssertions;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeSpec;
import com.wenjin.eval.scenario.EvalScenarioDocument.MasteryOverride;
import com.wenjin.eval.scenario.EvalScenarioDocument.MistakeSeed;
import com.wenjin.eval.scenario.EvalScenarioDocument.Persona;
import com.wenjin.eval.scenario.EvalScenarioDocument.RosterStudent;
import com.wenjin.eval.scenario.EvalScenarioDocument.ScriptTurn;
import com.wenjin.eval.scenario.EvalScenarioDocument.SnapshotSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Roster seeding of the teacher-assistant eval fixture, without a database (in-memory ports). */
class EvalFixtureRosterTest {

    private InMemoryEvalRosterPort roster;
    private InMemoryEvalMistakePort mistakes;
    private EvalFixture fixture;

    @BeforeEach
    void setUp() {
        roster = new InMemoryEvalRosterPort();
        mistakes = new InMemoryEvalMistakePort();
        fixture = new EvalFixture(
                new EvalWorld(1L, 2L, 3L, "EVAL_COURSE", Set.of("SE-1.1", "SE-3.2")),
                new InMemoryEvalMasteryPort(), new InMemoryEvalReviewQueuePort(), new InMemoryEvalSessionPort(),
                new MapEvalNodeLookup(3L, Map.of("SE-1.1", 10L, "SE-3.2", 11L)), mistakes, roster);
    }

    private static EvalScenarioDocument scenario(String actor, List<RosterStudent> students) {
        return new EvalScenarioDocument("T01", "t", new Persona(List.of(), List.of(), "n", null, students),
                List.of(new ScriptTurn("hi")), new HardAssertions(List.of(), List.of(), 3, true, true, false),
                new JudgeSpec(List.of(new JudgeDimension("expression", "x", 3))), "ENABLED", null, null, actor);
    }

    @Test
    @DisplayName("教师场景：评测学生退课，名册按槽位入库并按画像写入掌握度/快照/作答/私密对话")
    void teacherScenarioSeedsRoster() {
        RosterStudent atRisk = new RosterStudent("赵强",
                List.of(new MasteryOverride("SE-1.1", 0.30, null), new MasteryOverride("SE-3.2", 0.25, null)),
                List.of(new MistakeSeed("SE-3.2", "SE-1.1")), 2, 16,
                List.of(new SnapshotSeed("SE-3.2", 9, 0.90)), null, null);
        RosterStudent outsider = new RosterStudent("钱多多", List.of(new MasteryOverride("SE-1.1", 0.9, null)),
                null, null, null, null, true, "我私下问了伴侣一个秘密");

        fixture.prepareScenario(scenario("teacher", List.of(atRisk, outsider)));

        assertThat(roster.calls()).containsSubsequence("clearRoster", "primaryEnrolled=false", "clearTeacherState");
        assertThat(roster.primaryEnrolled()).isFalse();
        assertThat(roster.namesBySlot()).containsEntry(1, "赵强").containsEntry(2, "钱多多");
        assertThat(roster.enrolled()).containsExactly(1001L);
        assertThat(roster.otherCourseStudents()).containsExactly(1002L);
        assertThat(roster.masteries()).contains(
                new InMemoryEvalRosterPort.Mastery(1001L, 10L, 0.30),
                new InMemoryEvalRosterPort.Mastery(1001L, 11L, 0.25));
        // other-course students get no mastery in this course
        assertThat(roster.masteries()).noneMatch(m -> m.studentId() == 1002L);
        assertThat(roster.snapshots()).containsExactly(new InMemoryEvalRosterPort.Snapshot(1001L, 11L, 0.90, 9));
        // all answers sit 16 days back: wrong (via mistake port) and correct ones
        assertThat(mistakes.seeds()).hasSize(1);
        assertThat(mistakes.seeds().get(0).studentId()).isEqualTo(1001L);
        assertThat(mistakes.seeds().get(0).minutesAgo()).isGreaterThanOrEqualTo(16 * 1440);
        assertThat(roster.answers()).hasSize(2).allSatisfy(a -> {
            assertThat(a.correct()).isTrue();
            assertThat(a.nodeId()).isEqualTo(10L);
            assertThat(a.minutesAgo()).isGreaterThanOrEqualTo(16 * 1440);
        });
        assertThat(roster.privateChats()).containsEntry(1002L, "我私下问了伴侣一个秘密");
    }

    @Test
    @DisplayName("未指定 lastPracticeDaysAgo 时作答落在 1 天前")
    void defaultPracticeIsYesterday() {
        fixture.prepareScenario(scenario("teacher", List.of(new RosterStudent("李明",
                List.of(new MasteryOverride("SE-1.1", 0.9, null)), List.of(new MistakeSeed("SE-1.1", "SE-3.2")),
                1, null, null, null, null))));

        assertThat(mistakes.seeds().get(0).minutesAgo()).isBetween(1440, 1440 + 60);
        assertThat(roster.answers().get(0).minutesAgo()).isBetween(1440, 1440 + 60);
    }

    @Test
    @DisplayName("学生场景：清空上一场景遗留的名册并让评测学生回到课程，不写任何名册数据")
    void studentScenarioClearsRoster() {
        fixture.prepareScenario(scenario(null, null));

        assertThat(roster.calls()).containsExactly("clearRoster", "primaryEnrolled=true");
        assertThat(roster.primaryEnrolled()).isTrue();
        assertThat(roster.namesBySlot()).isEmpty();
        assertThat(roster.masteries()).isEmpty();
    }

    @Test
    @DisplayName("名册引用不存在的知识点：记入 missing，不写入")
    void unknownNodeIsReported() {
        EvalFixture.RosterInjectionResult result = fixture.applyRoster(List.of(new RosterStudent("甲",
                List.of(new MasteryOverride("NOPE", 0.5, null)), null, null, null, null, null, null)));

        assertThat(result.missingNodeCodes()).containsExactly("NOPE");
        assertThat(roster.masteries()).isEmpty();
        assertThat(result.studentIdsByName()).containsEntry("甲", 1001L);
    }
}
