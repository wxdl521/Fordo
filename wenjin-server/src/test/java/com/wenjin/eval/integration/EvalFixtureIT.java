package com.wenjin.eval.integration;

import com.wenjin.eval.fixture.EvalCourseCatalog;
import com.wenjin.eval.fixture.EvalFixture;
import com.wenjin.eval.fixture.EvalWorld;
import com.wenjin.eval.fixture.EvalWorldBuilder;
import com.wenjin.eval.runner.EvalHarness;
import com.wenjin.eval.scenario.EvalScenarioDocument.MasteryOverride;
import com.wenjin.eval.scenario.EvalScenarioDocument.Persona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * EvalFixture DB path: idempotent world build, persona injection (insertEnsure + snapshot + decay backdate +
 * review pins) and reset. Unit coverage of persona logic lives in {@code EvalFixtureTest}.
 * <p>Gated by {@code WENJIN_EVAL_IT=true}; DB = Testcontainers or {@code -Dwenjin.eval.jdbc-url}.
 */
@EnabledIfEnvironmentVariable(named = "WENJIN_EVAL_IT", matches = "true")
class EvalFixtureIT extends EvalDbSupport {

    @Autowired private EvalHarness harness;
    @Autowired private EvalWorldBuilder worldBuilder;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("world build is idempotent and seeds nodes, edges and APPROVED questions")
    void worldBuild_idempotent() {
        EvalWorld first = harness.fixture().world();
        EvalWorld again = worldBuilder.ensureWorld();
        assertThat(again).isEqualTo(first);
        assertThat(first.nodeCodes()).containsAll(EvalCourseCatalog.nodeCodes());

        Integer nodes = jdbc.queryForObject("SELECT COUNT(*) FROM kg_node WHERE course_id = ?", Integer.class,
                first.courseId());
        Integer edges = jdbc.queryForObject("SELECT COUNT(*) FROM kg_edge WHERE course_id = ?", Integer.class,
                first.courseId());
        Integer questions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question WHERE course_id = ? AND status = 1", Integer.class, first.courseId());
        assertThat(nodes).isEqualTo(EvalCourseCatalog.NODES.size());
        assertThat(edges).isEqualTo(EvalCourseCatalog.EDGES.size());
        assertThat(questions).isEqualTo(EvalCourseCatalog.NODES.size() * EvalCourseCatalog.QUESTIONS_PER_NODE);
    }

    @Test
    @DisplayName("DB path: persona mastery + reviewQueue injection reads back; reset clears it")
    void personaInjection_dbPath() {
        EvalFixture fixture = harness.fixture();
        fixture.resetStudentState();
        EvalFixture.PersonaInjectionResult result = fixture.applyPersona(new Persona(
                List.of(new MasteryOverride("SE-3.2", 0.35, 0.30)),
                List.of("SE-2.1"),
                "note"));

        assertThat(result.missingNodeCodes()).isEmpty();
        assertThat(result.appliedMasteryCodes()).containsExactly("SE-3.2");
        assertThat(result.appliedReviewCodes()).containsExactly("SE-2.1");
        assertThat(result.rawByCode().get("SE-3.2")).isCloseTo(0.35, within(0.001));
        assertThat(result.effectiveByCode().get("SE-3.2")).isLessThan(result.rawByCode().get("SE-3.2"));
        assertThat(result.reviewPins()).containsKey("SE-2.1");

        fixture.resetStudentState();
        Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM student_mastery WHERE student_id = ?",
                Integer.class, fixture.world().studentId());
        assertThat(left).isZero();
    }
}
