package com.wenjin.eval.integration;

import com.wenjin.config.FakeAiConfiguration;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentCourse;
import com.wenjin.entity.SysUser;
import com.wenjin.eval.fixture.DbEvalMasteryPort;
import com.wenjin.eval.fixture.EvalFixture;
import com.wenjin.eval.fixture.EvalWorld;
import com.wenjin.eval.fixture.JdbcEvalSessionPort;
import com.wenjin.eval.fixture.MapperEvalNodeLookup;
import com.wenjin.eval.fixture.StoreEvalReviewQueuePort;
import com.wenjin.eval.scenario.EvalScenarioDocument.MasteryOverride;
import com.wenjin.eval.scenario.EvalScenarioDocument.Persona;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.MasterySnapshotMapper;
import com.wenjin.mapper.StudentCourseMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.UserRole;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.MasteryService;
import com.wenjin.service.companion.ReviewScheduleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * EvalFixture IT skeleton: Testcontainers MySQL when Docker is available.
 * <p>Unit coverage of persona injection lives in {@code EvalFixtureTest} (no container).
 * This class proves DB adapters (insertEnsure + snapshot + schedule store) end-to-end.
 * <p>Skipped unless {@code WENJIN_EVAL_IT=true} (optional full Docker LIVE path).
 */
@SpringBootTest
@ActiveProfiles({"it", "eval"})
@Import(FakeAiConfiguration.class)
@Testcontainers
@EnabledIfEnvironmentVariable(named = "WENJIN_EVAL_IT", matches = "true")
class EvalFixtureIT {

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

    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private StudentCourseMapper studentCourseMapper;
    @Autowired private KgNodeMapper kgNodeMapper;
    @Autowired private StudentMasteryMapper studentMasteryMapper;
    @Autowired private MasterySnapshotMapper masterySnapshotMapper;
    @Autowired private MasteryService masteryService;
    @Autowired private MasteryDecayCalculator decayCalculator;
    @Autowired private ReviewScheduleStore scheduleStore;
    @Autowired private JdbcTemplate jdbcTemplate;

    private EvalFixture fixture;

    @BeforeEach
    void seedWorld() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{
                "answer_record", "student_mastery", "mastery_snapshot",
                "companion_message", "companion_conversation",
                "practice_session", "agent_step", "agent_run",
                "kg_edge", "kg_node", "student_course", "course", "sys_user"
        }) {
            try {
                jdbcTemplate.execute("DELETE FROM `" + table + "`");
            } catch (Exception ignored) {
                // optional tables
            }
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        scheduleStore.clear();

        SysUser teacher = new SysUser();
        teacher.setUsername("eval_teacher");
        teacher.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        teacher.setRole(UserRole.TEACHER.code());
        teacher.setStatus(1);
        sysUserMapper.insert(teacher);

        SysUser student = new SysUser();
        student.setUsername("eval_student");
        student.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        student.setRole(UserRole.STUDENT.code());
        student.setStatus(1);
        sysUserMapper.insert(student);

        Course course = new Course();
        course.setCode("EVAL_COURSE");
        course.setName("Eval Course");
        course.setTeacherId(teacher.getId());
        course.setStatus(1);
        courseMapper.insert(course);

        StudentCourse sc = new StudentCourse();
        sc.setStudentId(student.getId());
        sc.setCourseId(course.getId());
        studentCourseMapper.insert(sc);

        KgNode n1 = new KgNode();
        n1.setCourseId(course.getId());
        n1.setNodeCode("SE-3.2");
        n1.setName("用例图");
        n1.setChapter("需求分析");
        kgNodeMapper.insert(n1);

        KgNode n2 = new KgNode();
        n2.setCourseId(course.getId());
        n2.setNodeCode("SE-2.1");
        n2.setName("需求获取");
        n2.setChapter("需求分析");
        kgNodeMapper.insert(n2);

        EvalWorld world = new EvalWorld(
                teacher.getId(),
                student.getId(),
                course.getId(),
                course.getCode(),
                Set.of("SE-3.2", "SE-2.1"));

        fixture = new EvalFixture(
                world,
                new DbEvalMasteryPort(
                        studentMasteryMapper, masterySnapshotMapper,
                        masteryService, decayCalculator, jdbcTemplate),
                new StoreEvalReviewQueuePort(scheduleStore),
                new JdbcEvalSessionPort(jdbcTemplate),
                new MapperEvalNodeLookup(kgNodeMapper));
    }

    @Test
    @DisplayName("DB 路径: persona mastery + reviewQueue 注入可回读")
    void personaInjection_dbPath() {
        EvalFixture.PersonaInjectionResult result = fixture.applyPersona(new Persona(
                List.of(new MasteryOverride("SE-3.2", 0.35, 0.30)),
                List.of("SE-2.1"),
                "note"));

        assertThat(result.appliedMasteryCodes()).containsExactly("SE-3.2");
        assertThat(result.appliedReviewCodes()).containsExactly("SE-2.1");
        assertThat(result.rawByCode().get("SE-3.2")).isCloseTo(0.35, within(0.001));
        assertThat(result.effectiveByCode().get("SE-3.2"))
                .isLessThan(result.rawByCode().get("SE-3.2"));
        assertThat(result.reviewPins()).containsKey("SE-2.1");

        fixture.resetStudentState();
        assertThat(fixture.applyPersona(new Persona(List.of(), List.of(), null))
                .rawByCode()).isEmpty();
    }
}
