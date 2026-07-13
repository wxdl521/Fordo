package com.wenjin.integration;

import com.wenjin.dto.GradedAnswer;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.service.MasteryService;
import com.wenjin.support.QuestionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 掌握度并发：两线程对同一节点施加相反结果，最终分数串行化两事件，快照 +2。
 */
@SpringBootTest
@ActiveProfiles("it")
class MasteryConcurrencyIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "&useAffectedRows=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("WENJIN_AUTH_SECRET", () -> "it-test-secret-not-for-prod");
        registry.add("wenjin.auth.secret", () -> "it-test-secret-not-for-prod");
        registry.add("wenjin.mastery.alpha", () -> "0.3");
        registry.add("wenjin.mastery.mastered-threshold", () -> "75");
        registry.add("wenjin.mastery.weak-threshold", () -> "40");
    }

    @Autowired private MasteryService masteryService;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private KgNodeMapper kgNodeMapper;
    @Autowired private QuestionMapper questionMapper;
    @Autowired private QuestionNodeMapper questionNodeMapper;
    @Autowired private StudentMasteryMapper studentMasteryMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private long studentId;
    private long courseId;
    private long nodeId;
    private long qCorrectId;
    private long qWrongId;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{
                "mastery_snapshot", "student_mastery",
                "question_node", "question",
                "kg_node", "student_course", "course", "sys_user"
        }) {
            try {
                jdbcTemplate.execute("DELETE FROM `" + table + "`");
            } catch (Exception ignored) {
                // table may be absent on partial migrate
            }
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");

        SysUser student = new SysUser();
        student.setUsername("it_mastery_cc");
        student.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        student.setRole(2);
        student.setStatus(1);
        sysUserMapper.insert(student);
        studentId = student.getId();

        Course course = new Course();
        course.setCode("IT_MASTERY_CC");
        course.setName("Mastery CC");
        course.setStatus(1);
        courseMapper.insert(course);
        courseId = course.getId();

        KgNode node = new KgNode();
        node.setCourseId(courseId);
        node.setNodeCode("M1");
        node.setName("并发节点");
        node.setChapter("章一");
        node.setIsKey(1);
        kgNodeMapper.insert(node);
        nodeId = node.getId();

        // 初始掌握度 50（薄弱），两线程分别答对/答错同一节点
        StudentMastery sm = new StudentMastery();
        sm.setStudentId(studentId);
        sm.setCourseId(courseId);
        sm.setNodeId(nodeId);
        sm.setMasteryScore(new BigDecimal("50.00"));
        sm.setMasteryLevel(1);
        studentMasteryMapper.insert(sm);

        qCorrectId = insertQuestion("对题", nodeId);
        qWrongId = insertQuestion("错题", nodeId);
    }

    private long insertQuestion(String stem, long nId) {
        Question q = new Question();
        q.setCourseId(courseId);
        q.setStem(stem);
        q.setStemHash(com.wenjin.support.StemHash.sha256Hex(stem));
        q.setType(1);
        q.setDifficulty(3);
        q.setStatus(QuestionStatus.APPROVED);
        questionMapper.insert(q);

        QuestionNode qn = new QuestionNode();
        qn.setQuestionId(q.getId());
        qn.setNodeId(nId);
        qn.setWeight(1);
        questionNodeMapper.insert(qn);
        return q.getId();
    }

    @Test
    @DisplayName("IT：两线程对同一掌握度施加对/错 → 最终分含两事件，快照+2")
    void concurrentOppositeAnswers_bothApplied() throws Exception {
        long snapsBefore = countSnapshots();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> f1 = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                masteryService.applyAnswers(studentId, courseId,
                        List.of(new GradedAnswer(qCorrectId, true)));
                return null;
            });
            Future<?> f2 = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                masteryService.applyAnswers(studentId, courseId,
                        List.of(new GradedAnswer(qWrongId, false)));
                return null;
            });
            start.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        StudentMastery finalRow = studentMasteryMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StudentMastery>()
                        .eq(StudentMastery::getStudentId, studentId)
                        .eq(StudentMastery::getCourseId, courseId)
                        .eq(StudentMastery::getNodeId, nodeId));
        assertThat(finalRow).isNotNull();

        // 两事件串行（顺序不定）：
        // 先对后错：50 → 65.00 → 45.50
        // 先错后对：50 → 35.00 → 54.50
        BigDecimal score = finalRow.getMasteryScore().setScale(2, RoundingMode.HALF_UP);
        assertThat(score).isIn(new BigDecimal("45.50"), new BigDecimal("54.50"));

        // 不得出现丢失更新后的单事件终态（65.00 或 35.00）
        assertThat(score).isNotEqualByComparingTo("65.00");
        assertThat(score).isNotEqualByComparingTo("35.00");
        assertThat(score).isNotEqualByComparingTo("50.00");

        long snapsAfter = countSnapshots();
        assertThat(snapsAfter - snapsBefore).isEqualTo(2);
    }

    @Test
    @DisplayName("IT：diagnostic 外层事务写 mastery/snapshot 后失败 → 两表均保持原值")
    void diagnosticOuterTransactionRollbackKeepsMasteryAndSnapshotsUnchanged() {
        StudentMastery before = loadMastery();
        long snapshotsBefore = countSnapshots();
        RuntimeException failure = new RuntimeException("force diagnostic rollback");
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            masteryService.applyAnswers(studentId, courseId,
                    List.of(new GradedAnswer(qCorrectId, true)));
            throw failure;
        })).isSameAs(failure);

        StudentMastery after = loadMastery();
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getMasteryScore()).isEqualByComparingTo(before.getMasteryScore());
        assertThat(after.getMasteryLevel()).isEqualTo(before.getMasteryLevel());
        assertThat(countSnapshots()).isEqualTo(snapshotsBefore);
    }

    private StudentMastery loadMastery() {
        return studentMasteryMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StudentMastery>()
                        .eq(StudentMastery::getStudentId, studentId)
                        .eq(StudentMastery::getCourseId, courseId)
                        .eq(StudentMastery::getNodeId, nodeId));
    }

    private long countSnapshots() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mastery_snapshot WHERE student_id = ? AND course_id = ? AND node_id = ?",
                Long.class, studentId, courseId, nodeId);
        return n == null ? 0L : n;
    }
}
