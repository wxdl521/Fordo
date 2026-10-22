package com.wenjin.eval.scenario;

import com.wenjin.eval.scenario.EvalScenarioDocument.HardAssertions;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeSpec;
import com.wenjin.eval.scenario.EvalScenarioDocument.MasteryOverride;
import com.wenjin.eval.scenario.EvalScenarioDocument.MistakeSeed;
import com.wenjin.eval.scenario.EvalScenarioDocument.Persona;
import com.wenjin.eval.scenario.EvalScenarioDocument.RosterStudent;
import com.wenjin.eval.scenario.EvalScenarioDocument.ScriptTurn;
import com.wenjin.eval.scenario.EvalScenarioDocument.SnapshotSeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Schema additions of the teacher-assistant eval: {@code actor}, {@code persona.roster}, T## codes, new hard keys. */
class TeacherScenarioSchemaTest {

    private static RosterStudent student(String name) {
        return new RosterStudent(name, List.of(new MasteryOverride("SE-1.1", 0.5, null)),
                null, null, null, null, null, null);
    }

    private static EvalScenarioDocument teacherDoc(String code, String actor, List<RosterStudent> roster,
                                                   HardAssertions hard) {
        return new EvalScenarioDocument(code, "t", new Persona(List.of(), List.of(), "n", null, roster),
                List.of(new ScriptTurn("hi")), hard,
                new JudgeSpec(List.of(new JudgeDimension("expression", "x", 3))), "ENABLED", null, null, actor);
    }

    private static HardAssertions hard() {
        return new HardAssertions(List.of(), List.of(), 3, true, true, false);
    }

    @Test
    @DisplayName("T## code + actor=teacher + roster 通过校验；actor 缺省为 student")
    void teacherDocumentPasses() {
        EvalScenarioDocument doc = teacherDoc("T01", "teacher", List.of(student("李明"), student("陈静")), hard());
        assertThatCode(() -> ScenarioSchemaValidator.validate(doc)).doesNotThrowAnyException();
        assertThat(doc.forTeacher()).isTrue();
        assertThat(teacherDoc("S01", null, null, hard()).resolvedActor()).isEqualTo("student");
    }

    @Test
    @DisplayName("S/T 之外的前缀仍被拒绝")
    void otherPrefixRejected() {
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(teacherDoc("X01", "teacher", null, hard())))
                .isInstanceOf(ScenarioSchemaException.class).hasMessageContaining("code must match");
    }

    @Test
    @DisplayName("未知 actor 失败")
    void unknownActorRejected() {
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(teacherDoc("T01", "admin", null, hard())))
                .isInstanceOf(ScenarioSchemaException.class).hasMessageContaining("actor");
    }

    @Test
    @DisplayName("学生场景不得带 roster")
    void rosterOnlyForTeacher() {
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(
                teacherDoc("S01", "student", List.of(student("李明")), hard())))
                .isInstanceOf(ScenarioSchemaException.class).hasMessageContaining("roster");
    }

    @Test
    @DisplayName("roster 超过 5 人失败")
    void rosterTooLarge() {
        List<RosterStudent> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(student("学生" + i));
        }
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(teacherDoc("T01", "teacher", six, hard())))
                .isInstanceOf(ScenarioSchemaException.class).hasMessageContaining("roster");
    }

    @Test
    @DisplayName("roster 姓名缺失/重复失败")
    void rosterNames() {
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(
                teacherDoc("T01", "teacher", List.of(student(" ")), hard())))
                .isInstanceOf(ScenarioSchemaException.class).hasMessageContaining("roster[0].name");
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(
                teacherDoc("T01", "teacher", List.of(student("李明"), student("李明")), hard())))
                .isInstanceOf(ScenarioSchemaException.class).hasMessageContaining("duplicated");
    }

    @Test
    @DisplayName("roster 掌握度：缺 nodeCode / raw 越界 / 带 effective 失败")
    void rosterMastery() {
        RosterStudent noCode = new RosterStudent("甲", List.of(new MasteryOverride(" ", 0.5, null)),
                null, null, null, null, null, null);
        RosterStudent badRaw = new RosterStudent("乙", List.of(new MasteryOverride("SE-1.1", 1.5, null)),
                null, null, null, null, null, null);
        RosterStudent withEffective = new RosterStudent("丙", List.of(new MasteryOverride("SE-1.1", 0.5, 0.4)),
                null, null, null, null, null, null);
        for (RosterStudent bad : List.of(noCode, badRaw, withEffective)) {
            assertThatThrownBy(() -> ScenarioSchemaValidator.validate(
                    teacherDoc("T01", "teacher", List.of(bad), hard())))
                    .isInstanceOf(ScenarioSchemaException.class).hasMessageContaining("roster[0].masteryOverrides");
        }
    }

    @Test
    @DisplayName("roster 的 lastPracticeDaysAgo / correctAnswers / snapshots / mistakes 范围校验")
    void rosterNumbers() {
        List<RosterStudent> bad = List.of(
                new RosterStudent("甲", null, null, null, -1, null, null, null),
                new RosterStudent("甲", null, null, null, 400, null, null, null),
                new RosterStudent("甲", null, null, -1, null, null, null, null),
                new RosterStudent("甲", null, null, 51, null, null, null, null),
                new RosterStudent("甲", null, null, null, null, List.of(new SnapshotSeed("SE-1.1", 0, 0.5)), null, null),
                new RosterStudent("甲", null, null, null, null, List.of(new SnapshotSeed("SE-1.1", 5, 1.5)), null, null),
                new RosterStudent("甲", null, null, null, null, List.of(new SnapshotSeed(" ", 5, 0.5)), null, null),
                new RosterStudent("甲", null, List.of(new MistakeSeed("SE-1.1", " ")), null, null, null, null, null));
        for (RosterStudent s : bad) {
            assertThatThrownBy(() -> ScenarioSchemaValidator.validate(
                    teacherDoc("T01", "teacher", List.of(s), hard())))
                    .as(s.toString())
                    .isInstanceOf(ScenarioSchemaException.class).hasMessageContaining("roster[0]");
        }
    }

    @Test
    @DisplayName("hard.replyMustContain / guardCodesExpected 元素不得为空白")
    void newHardKeys() {
        HardAssertions blankContain = new HardAssertions(List.of(), List.of(), 3, true, true, false, null, null,
                List.of(" "), null);
        HardAssertions blankGuard = new HardAssertions(List.of(), List.of(), 3, true, true, false, null, null,
                null, List.of(""));
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(teacherDoc("T01", "teacher", null, blankContain)))
                .isInstanceOf(ScenarioSchemaException.class).hasMessageContaining("replyMustContain");
        assertThatThrownBy(() -> ScenarioSchemaValidator.validate(teacherDoc("T01", "teacher", null, blankGuard)))
                .isInstanceOf(ScenarioSchemaException.class).hasMessageContaining("guardCodesExpected");
    }

    @Test
    @DisplayName("invalid/ 夹具：未知 actor、学生场景带 roster、名册重名被加载器拒绝")
    void invalidFixtures() throws Exception {
        for (String[] f : new String[][]{{"eval/invalid/unknown-actor.json", "actor"},
                {"eval/invalid/student-with-roster.json", "roster"},
                {"eval/invalid/roster-duplicate-name.json", "duplicated"}}) {
            try (InputStream in = new ClassPathResource(f[0]).getInputStream()) {
                assertThatThrownBy(() -> new ScenarioLoader().parseAndValidate(in, f[0]))
                        .as(f[0]).isInstanceOf(ScenarioSchemaException.class).hasMessageContaining(f[1]);
            }
        }
    }
}
