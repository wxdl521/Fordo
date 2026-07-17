package com.wenjin.integration;

import com.wenjin.entity.CompanionConversation;
import com.wenjin.entity.Course;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.CompanionConversationMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.UserRole;
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

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-1 IT (Testcontainers MySQL): summary write guard against node_code lost-update;
 * watermark (summary_through_message_id) must not regress.
 * <p>Unit-level contract also covered by {@code CompanionSummaryGuardTest}.
 */
@SpringBootTest
@ActiveProfiles("it")
class CompanionSummaryGuardIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        // useAffectedRows=true: UPDATE returns 0 when WHERE does not match
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?useAffectedRows=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("WENJIN_AUTH_SECRET", () -> "it-test-secret-not-for-prod-at-least-32-bytes");
        registry.add("wenjin.auth.secret", () -> "it-test-secret-not-for-prod-at-least-32-bytes");
        registry.add("wenjin.ai.enabled", () -> "false");
    }

    @Autowired private CompanionConversationMapper conversationMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private long studentId;
    private long courseId;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{
                "companion_message", "companion_conversation",
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
        teacher.setUsername("it_summary_guard_teacher");
        teacher.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        teacher.setRole(UserRole.TEACHER.code());
        teacher.setStatus(1);
        sysUserMapper.insert(teacher);

        SysUser student = new SysUser();
        student.setUsername("it_summary_guard_student");
        student.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        student.setRole(2);
        student.setStatus(1);
        sysUserMapper.insert(student);
        studentId = student.getId();

        Course course = new Course();
        course.setCode("IT_SUMMARY_GUARD");
        course.setName("Summary Guard");
        course.setTeacherId(teacher.getId());
        course.setStatus(1);
        courseMapper.insert(course);
        courseId = course.getId();
    }

    @Test
    @DisplayName("concurrentSwitchNotClobbered: summary write after node switch keeps NEW node_code")
    void concurrentSwitchNotClobbered() throws Exception {
        CompanionConversation conv = newConversation("OLD_NODE");
        conversationMapper.insert(conv);
        final long convId = conv.getId();

        CountDownLatch aLoaded = new CountDownLatch(1);
        CountDownLatch bSwitched = new CountDownLatch(1);
        AtomicInteger guardedRows = new AtomicInteger(-1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Future<?> futureA = pool.submit(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    CompanionConversation stale = conversationMapper.selectById(convId);
                    assertThat(stale).isNotNull();
                    assertThat(stale.getNodeCode()).isEqualTo("OLD_NODE");
                    aLoaded.countDown();
                    await(bSwitched, "B switch");

                    int rows = conversationMapper.updateSummaryGuarded(
                            convId, "compressed-history-summary", 80L);
                    guardedRows.set(rows);
                });
            } catch (Throwable t) {
                error.set(t);
                aLoaded.countDown();
            }
        });

        Future<?> futureB = pool.submit(() -> {
            try {
                await(aLoaded, "A load");
                tx.executeWithoutResult(status -> {
                    CompanionConversation live = conversationMapper.selectById(convId);
                    assertThat(live).isNotNull();
                    live.setNodeCode("NEW_NODE");
                    live.setUpdatedAt(LocalDateTime.now());
                    conversationMapper.updateById(live);
                });
                bSwitched.countDown();
            } catch (Throwable t) {
                error.set(t);
                bSwitched.countDown();
            }
        });

        futureA.get(30, TimeUnit.SECONDS);
        futureB.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(error.get()).as("no thread errors").isNull();
        assertThat(guardedRows.get()).isEqualTo(1);

        CompanionConversation after = conversationMapper.selectById(convId);
        assertThat(after.getNodeCode())
                .as("node_code must not be clobbered by summary write")
                .isEqualTo("NEW_NODE");
        assertThat(after.getSummaryText()).isEqualTo("compressed-history-summary");
        assertThat(after.getSummaryThroughMessageId()).isEqualTo(80L);
    }

    @Test
    @DisplayName("watermarkNeverRegresses: stale throughId returns 0 and leaves DB unchanged")
    void watermarkNeverRegresses() {
        CompanionConversation conv = newConversation("N1");
        conv.setSummaryText("already-advanced");
        conv.setSummaryThroughMessageId(100L);
        conversationMapper.insert(conv);
        long convId = conv.getId();

        int rows = conversationMapper.updateSummaryGuarded(convId, "stale-summary", 50L);
        assertThat(rows).as("stale watermark must not apply").isEqualTo(0);

        CompanionConversation after = conversationMapper.selectById(convId);
        assertThat(after.getSummaryThroughMessageId()).isEqualTo(100L);
        assertThat(after.getSummaryText()).isEqualTo("already-advanced");

        int advanced = conversationMapper.updateSummaryGuarded(convId, "fresh-summary", 150L);
        assertThat(advanced).isEqualTo(1);
        CompanionConversation advancedRow = conversationMapper.selectById(convId);
        assertThat(advancedRow.getSummaryThroughMessageId()).isEqualTo(150L);
        assertThat(advancedRow.getSummaryText()).isEqualTo("fresh-summary");
    }

    private CompanionConversation newConversation(String nodeCode) {
        CompanionConversation conv = new CompanionConversation();
        conv.setStudentId(studentId);
        conv.setCourseId(courseId);
        conv.setTitle("guard-test");
        conv.setNodeCode(nodeCode);
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        return conv;
    }

    private static void await(CountDownLatch latch, String label) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timeout waiting for " + label);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for " + label, e);
        }
    }
}
