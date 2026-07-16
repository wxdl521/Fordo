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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
}
