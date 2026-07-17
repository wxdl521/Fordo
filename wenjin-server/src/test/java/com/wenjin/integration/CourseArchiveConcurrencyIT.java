package com.wenjin.integration;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.entity.Course;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.TeacherCourseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Real-MySQL regression for archive/publish serialization on the shared course row lock. */
@SpringBootTest
@ActiveProfiles("it")
class CourseArchiveConcurrencyIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("WENJIN_AUTH_SECRET", () -> "it-test-secret-not-for-prod-at-least-32-bytes");
        registry.add("wenjin.auth.secret", () -> "it-test-secret-not-for-prod-at-least-32-bytes");
        registry.add("wenjin.features.graph-reconcile-enabled", () -> "true");
    }

    @Autowired private TeacherCourseService teacherCourseService;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long teacherId;
    private long courseId;

    @BeforeEach
    void seedCourse() {
        CurrentUser.clear();
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{
                "answer_record", "student_mastery", "mastery_snapshot",
                "diagnostic_session_question", "diagnostic_session",
                "question_option", "question_node", "question",
                "kg_edge", "kg_node", "student_course", "course", "sys_user"
        }) {
            try {
                jdbcTemplate.execute("DELETE FROM `" + table + "`");
            } catch (Exception ignored) {
                // Table may not exist in an older migration snapshot.
            }
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");

        SysUser teacher = new SysUser();
        teacher.setUsername("archive-publish-race");
        teacher.setPassword("x");
        teacher.setRole(UserRole.TEACHER.code());
        teacher.setStatus(1);
        sysUserMapper.insert(teacher);
        teacherId = teacher.getId();

        Course course = new Course();
        course.setCode("ARCHIVE01");
        course.setName("archive-publish-race");
        course.setTeacherId(teacherId);
        course.setStatus(1);
        courseMapper.insert(course);
        courseId = course.getId();
    }

    @AfterEach
    void clearUser() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("archive 与 publish 并发后，课程必须保持已归档且未发布")
    void concurrentArchiveAndPublishLeavesCourseArchivedAndUnpublished() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<Void> archive = executor.submit(() -> {
                CurrentUser.set(new CurrentPrincipal(teacherId, UserRole.TEACHER));
                try {
                    start.await(10, TimeUnit.SECONDS);
                    teacherCourseService.delete(courseId);
                    return null;
                } finally {
                    CurrentUser.clear();
                }
            });
            Future<Integer> publish = executor.submit(() -> {
                CurrentUser.set(new CurrentPrincipal(teacherId, UserRole.TEACHER));
                try {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        teacherCourseService.setPublished(courseId, true);
                        return 0;
                    } catch (BusinessException ex) {
                        assertThat(ex.getCode()).isEqualTo(ResultCode.NOT_FOUND.getCode());
                        return ex.getCode();
                    }
                } finally {
                    CurrentUser.clear();
                }
            });

            archive.get(30, TimeUnit.SECONDS);
            assertThat(publish.get(30, TimeUnit.SECONDS))
                    .isIn(0, ResultCode.NOT_FOUND.getCode());
        } finally {
            executor.shutdownNow();
        }

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, archived_at FROM course WHERE id = ?", courseId);
        assertThat(((Number) row.get("status")).intValue()).isZero();
        assertThat(row.get("archived_at")).isNotNull();
    }

    @Test
    @DisplayName("restore 只恢复课程级联归档的图行，保留此前单独归档的节点和边")
    void restorePreservesIndependentlyArchivedGraphRows() {
        LocalDateTime independentlyArchivedAt = LocalDateTime.now().minusDays(1).withNano(0);
        jdbcTemplate.update(
                "INSERT INTO kg_node(course_id, node_code, name, archived_at) VALUES (?, ?, ?, ?)",
                courseId, "ARCHIVED_NODE", "archived-node", independentlyArchivedAt);
        jdbcTemplate.update(
                "INSERT INTO kg_node(course_id, node_code, name) VALUES (?, ?, ?), (?, ?, ?)",
                courseId, "ACTIVE_A", "active-a", courseId, "ACTIVE_B", "active-b");

        long activeA = jdbcTemplate.queryForObject(
                "SELECT id FROM kg_node WHERE course_id = ? AND node_code = 'ACTIVE_A'", Long.class, courseId);
        long activeB = jdbcTemplate.queryForObject(
                "SELECT id FROM kg_node WHERE course_id = ? AND node_code = 'ACTIVE_B'", Long.class, courseId);
        jdbcTemplate.update(
                "INSERT INTO kg_edge(course_id, from_node_id, to_node_id, relation_type, archived_at) VALUES (?, ?, ?, ?, ?)",
                courseId, activeA, activeB, 3, independentlyArchivedAt);
        jdbcTemplate.update(
                "INSERT INTO kg_edge(course_id, from_node_id, to_node_id, relation_type) VALUES (?, ?, ?, ?)",
                courseId, activeB, activeA, 4);

        CurrentUser.set(new CurrentPrincipal(teacherId, UserRole.TEACHER));
        teacherCourseService.delete(courseId);
        teacherCourseService.restore(courseId);

        Map<String, Object> archivedNode = jdbcTemplate.queryForMap(
                "SELECT archived_at, archived_by_course FROM kg_node WHERE course_id = ? AND node_code = 'ARCHIVED_NODE'",
                courseId);
        Map<String, Object> activeNode = jdbcTemplate.queryForMap(
                "SELECT archived_at, archived_by_course FROM kg_node WHERE id = ?", activeA);
        Map<String, Object> archivedEdge = jdbcTemplate.queryForMap(
                "SELECT archived_at, archived_by_course FROM kg_edge WHERE from_node_id = ? AND to_node_id = ? AND relation_type = 3",
                activeA, activeB);
        Map<String, Object> activeEdge = jdbcTemplate.queryForMap(
                "SELECT archived_at, archived_by_course FROM kg_edge WHERE from_node_id = ? AND to_node_id = ? AND relation_type = 4",
                activeB, activeA);

        assertThat(archivedNode.get("archived_at")).isNotNull();
        assertThat(booleanValue(archivedNode.get("archived_by_course"))).isFalse();
        assertThat(activeNode.get("archived_at")).isNull();
        assertThat(booleanValue(activeNode.get("archived_by_course"))).isFalse();
        assertThat(archivedEdge.get("archived_at")).isNotNull();
        assertThat(booleanValue(archivedEdge.get("archived_by_course"))).isFalse();
        assertThat(activeEdge.get("archived_at")).isNull();
        assertThat(booleanValue(activeEdge.get("archived_by_course"))).isFalse();

        Throwable nodeMarkerFailure = catchThrowable(() -> jdbcTemplate.update(
                "UPDATE kg_node SET archived_at = NOW(), archived_by_course = 2 WHERE id = ?", activeA));
        assertCheckViolation(nodeMarkerFailure, "chk_node_course_archive_marker");

        Throwable edgeMarkerFailure = catchThrowable(() -> jdbcTemplate.update(
                "UPDATE kg_edge SET archived_at = NOW(), archived_by_course = -1 "
                        + "WHERE from_node_id = ? AND to_node_id = ? AND relation_type = 4",
                activeB, activeA));
        assertCheckViolation(edgeMarkerFailure, "chk_edge_course_archive_marker");
    }

    private static void assertCheckViolation(Throwable failure, String constraintName) {
        assertThat(failure).isInstanceOf(DataAccessException.class);
        SQLException sqlException = sqlExceptionFromCauseChain(failure);
        assertThat(sqlException.getSQLState()).isEqualTo("HY000");
        assertThat(sqlException.getErrorCode()).isEqualTo(3819);
        assertThat(sqlException.getMessage()).contains(constraintName);
    }

    private static SQLException sqlExceptionFromCauseChain(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        throw new AssertionError("Expected SQLException in cause chain", failure);
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return ((Number) value).intValue() != 0;
    }
}
