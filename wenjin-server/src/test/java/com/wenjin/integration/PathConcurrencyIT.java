package com.wenjin.integration;

import com.wenjin.dto.LearningPathVO;
import com.wenjin.dto.PathGenerateRequest;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentCourse;
import com.wenjin.entity.StudentMastery;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentCourseMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.UserRole;
import com.wenjin.service.PathService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 学习路径并发：两线程同时 generate → 仅一条 active 路径，current_path_id 指向它。
 */
@SpringBootTest
@ActiveProfiles("it")
class PathConcurrencyIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?useAffectedRows=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("WENJIN_AUTH_SECRET", () -> "it-test-secret-not-for-prod-at-least-32-bytes");
        registry.add("wenjin.auth.secret", () -> "it-test-secret-not-for-prod-at-least-32-bytes");
        registry.add("wenjin.ai.enabled", () -> "false");
    }

    @Autowired private PathService pathService;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private StudentCourseMapper studentCourseMapper;
    @Autowired private KgNodeMapper kgNodeMapper;
    @Autowired private StudentMasteryMapper studentMasteryMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private long studentId;
    private long courseId;
    private long targetNodeId;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{
                "learning_path_item", "learning_path",
                "student_mastery", "kg_edge", "kg_node",
                "student_course", "course", "sys_user"
        }) {
            try {
                jdbcTemplate.execute("DELETE FROM `" + table + "`");
            } catch (Exception ignored) {
                // table may be absent on partial migrate
            }
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");

        SysUser teacher = new SysUser();
        teacher.setUsername("it_path_cc_teacher");
        teacher.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        teacher.setRole(UserRole.TEACHER.code());
        teacher.setStatus(1);
        sysUserMapper.insert(teacher);

        SysUser student = new SysUser();
        student.setUsername("it_path_cc");
        student.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        student.setRole(2);
        student.setStatus(1);
        sysUserMapper.insert(student);
        studentId = student.getId();

        Course course = new Course();
        course.setCode("IT_PATH_CC");
        course.setName("Path CC");
        course.setTeacherId(teacher.getId());
        course.setStatus(1);
        courseMapper.insert(course);
        courseId = course.getId();

        StudentCourse sc = new StudentCourse();
        sc.setStudentId(studentId);
        sc.setCourseId(courseId);
        studentCourseMapper.insert(sc);

        KgNode node = new KgNode();
        node.setCourseId(courseId);
        node.setNodeCode("T");
        node.setName("卡点");
        node.setChapter("章一");
        node.setIsKey(1);
        kgNodeMapper.insert(node);
        targetNodeId = node.getId();

        StudentMastery sm = new StudentMastery();
        sm.setStudentId(studentId);
        sm.setCourseId(courseId);
        sm.setNodeId(targetNodeId);
        sm.setMasteryScore(new BigDecimal("30.00"));
        sm.setMasteryLevel(0);
        studentMasteryMapper.insert(sm);
    }

    @Test
    @DisplayName("IT：两线程同时 generate → 仅一条 active，current_path_id 指向它")
    void concurrentGenerate_singleActivePath() throws Exception {
        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(studentId);
        req.setCourseId(courseId);
        req.setTargetNodeId(targetNodeId);
        req.setUseAi(false);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<LearningPathVO> results = new ArrayList<>();
        try {
            List<Future<LearningPathVO>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return pathService.generate(req);
                }));
            }
            start.countDown();
            results.add(futures.get(0).get(30, TimeUnit.SECONDS));
            results.add(futures.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getPathId()).isNotNull();
        assertThat(results.get(1).getPathId()).isNotNull();

        Long activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM learning_path WHERE student_id = ? AND course_id = ? AND status = 1",
                Long.class, studentId, courseId);
        assertThat(activeCount).isEqualTo(1L);

        Long totalPaths = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM learning_path WHERE student_id = ? AND course_id = ?",
                Long.class, studentId, courseId);
        // 两次 generate 各插一条，其一被置失效
        assertThat(totalPaths).isEqualTo(2L);

        Long currentPathId = jdbcTemplate.queryForObject(
                "SELECT current_path_id FROM student_course WHERE student_id = ? AND course_id = ?",
                Long.class, studentId, courseId);
        assertThat(currentPathId).isNotNull();

        Long activePathId = jdbcTemplate.queryForObject(
                "SELECT id FROM learning_path WHERE student_id = ? AND course_id = ? AND status = 1",
                Long.class, studentId, courseId);
        assertThat(currentPathId).isEqualTo(activePathId);

        LearningPathVO current = pathService.getCurrent(studentId, courseId);
        assertThat(current.getPathId()).isEqualTo(currentPathId);
        assertThat(current.getSteps()).isNotEmpty();
    }

    @Test
    @DisplayName("IT：practice 外层事务重写 path/current pointer 后失败 → 路径、步骤、指针均保持原值")
    void practiceOuterTransactionRollbackKeepsPathRowsAndPointerUnchanged() {
        PathGenerateRequest req = request();
        LearningPathVO original = pathService.generate(req);
        Long originalPathId = original.getPathId();
        Long pathsBefore = countPaths();
        Long itemsBefore = countPathItems();
        RuntimeException failure = new RuntimeException("force practice rollback");
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            pathService.generate(req);
            throw failure;
        })).isSameAs(failure);

        assertThat(countPaths()).isEqualTo(pathsBefore);
        assertThat(countPathItems()).isEqualTo(itemsBefore);
        assertThat(currentPathId()).isEqualTo(originalPathId);
        Long originalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM learning_path WHERE id = ?", Long.class, originalPathId);
        assertThat(originalStatus).isEqualTo(1L);
        assertThat(pathService.getCurrent(studentId, courseId).getPathId()).isEqualTo(originalPathId);
    }

    private PathGenerateRequest request() {
        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(studentId);
        req.setCourseId(courseId);
        req.setTargetNodeId(targetNodeId);
        req.setUseAi(false);
        return req;
    }

    private Long countPaths() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM learning_path WHERE student_id = ? AND course_id = ?",
                Long.class, studentId, courseId);
    }

    private Long countPathItems() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM learning_path_item i JOIN learning_path p ON p.id = i.path_id "
                        + "WHERE p.student_id = ? AND p.course_id = ?",
                Long.class, studentId, courseId);
    }

    private Long currentPathId() {
        return jdbcTemplate.queryForObject(
                "SELECT current_path_id FROM student_course WHERE student_id = ? AND course_id = ?",
                Long.class, studentId, courseId);
    }
}
