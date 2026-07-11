package com.wenjin.integration;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.AiJobVO;
import com.wenjin.entity.AiJob;
import com.wenjin.entity.Course;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.AiJobMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.AiJobService;
import com.wenjin.support.AiJobErrorCodes;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.AiJobStatus;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 作业恢复与领取 — 真实 MySQL（Testcontainers）集成测试。
 */
@SpringBootTest
@ActiveProfiles("it")
class AiJobRecoveryIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("WENJIN_AUTH_SECRET", () -> "it-test-secret-not-for-prod");
        registry.add("wenjin.auth.secret", () -> "it-test-secret-not-for-prod");
        // 集成测试不启动定时 tick 干扰领取
        registry.add("wenjin.jobs.poll-interval-ms", () -> "3600000");
    }

    @Autowired private AiJobService aiJobService;
    @Autowired private AiJobMapper aiJobMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FeatureFlagsProperties featureFlags;

    private long teacherAId;
    private long teacherBId;
    private long courseAId;

    @BeforeEach
    void seed() {
        CurrentUser.clear();
        featureFlags.setAiJobDispatchEnabled(true);
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{"ai_job", "course", "sys_user"}) {
            jdbcTemplate.execute("DELETE FROM `" + table + "`");
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");

        SysUser a = new SysUser();
        a.setUsername("ai_job_teacher_a");
        a.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        a.setRole(1);
        a.setStatus(1);
        sysUserMapper.insert(a);
        teacherAId = a.getId();

        SysUser b = new SysUser();
        b.setUsername("ai_job_teacher_b");
        b.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        b.setRole(1);
        b.setStatus(1);
        sysUserMapper.insert(b);
        teacherBId = b.getId();

        Course course = new Course();
        course.setCode("AI_JOB_COURSE_A");
        course.setName("AI Job Course A");
        course.setTeacherId(teacherAId);
        course.setStatus(1);
        courseMapper.insert(course);
        courseAId = course.getId();
    }

    @AfterEach
    void clear() {
        CurrentUser.clear();
        featureFlags.setAiJobDispatchEnabled(true);
    }

    @Test
    @DisplayName("两线程 claim 同一 PENDING：仅一人成功领取")
    void twoWorkersClaimSameJob_onlyOneWins() throws Exception {
        CurrentUser.set(new CurrentPrincipal(teacherAId, UserRole.TEACHER));
        AiJobVO created = aiJobService.dispatch(courseAId, AiJobKind.QUESTION_GENERATE, "claim-race",
                Map.of("nodeCode", "KT01", "count", 1));
        assertThat(created.getStatus()).isEqualTo(AiJobStatus.PENDING);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<AiJob> task = () -> aiJobService.claimNext();
            Future<AiJob> f1 = pool.submit(task);
            Future<AiJob> f2 = pool.submit(task);
            AiJob c1 = f1.get(10, TimeUnit.SECONDS);
            AiJob c2 = f2.get(10, TimeUnit.SECONDS);

            List<AiJob> claimed = new ArrayList<>();
            if (c1 != null) claimed.add(c1);
            if (c2 != null) claimed.add(c2);

            assertThat(claimed).hasSize(1);
            assertThat(claimed.get(0).getId()).isEqualTo(created.getId());
            assertThat(claimed.get(0).getStatus()).isEqualTo(AiJobStatus.RUNNING);

            AiJob row = aiJobMapper.selectById(created.getId());
            assertThat(row.getStatus()).isEqualTo(AiJobStatus.RUNNING);
            assertThat(row.getAttempts()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("同一 Idempotency-Key 返回原 job")
    void sameIdempotencyKey_returnsOriginal() {
        CurrentUser.set(new CurrentPrincipal(teacherAId, UserRole.TEACHER));
        AiJobVO a = aiJobService.dispatch(courseAId, AiJobKind.QUESTION_GENERATE, "idem-1",
                Map.of("nodeCode", "KT01", "count", 2));
        AiJobVO b = aiJobService.dispatch(courseAId, AiJobKind.QUESTION_GENERATE, "idem-1",
                Map.of("nodeCode", "KT01", "count", 2));
        assertThat(b.getId()).isEqualTo(a.getId());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_job WHERE owner_id=? AND request_key='idem-1'",
                Integer.class, teacherAId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("RUNNING 卡住 >15min 且 attempts<3 → 回 PENDING；attempts≥3 → FAILED")
    void staleRunning_recoveredOrExhausted() {
        AiJob requeue = insertRunning(teacherAId, courseAId, "stale-rq", 1,
                LocalDateTime.now().minusMinutes(AiJobService.STALE_MINUTES + 1));
        AiJob exhaust = insertRunning(teacherAId, courseAId, "stale-ex", AiJobService.MAX_ATTEMPTS,
                LocalDateTime.now().minusMinutes(AiJobService.STALE_MINUTES + 1));

        int touched = aiJobService.recoverStale();
        assertThat(touched).isGreaterThanOrEqualTo(2);

        AiJob rq = aiJobMapper.selectById(requeue.getId());
        assertThat(rq.getStatus()).isEqualTo(AiJobStatus.PENDING);
        assertThat(rq.getStartedAt()).isNull();
        assertThat(rq.getAttempts()).isEqualTo(1);

        AiJob ex = aiJobMapper.selectById(exhaust.getId());
        assertThat(ex.getStatus()).isEqualTo(AiJobStatus.FAILED);
        assertThat(ex.getErrorCode()).isEqualTo(AiJobErrorCodes.STALE_EXHAUSTED);
    }

    @Test
    @DisplayName("owner 可读；跨教师 404")
    void ownerRead_crossTeacher404() {
        CurrentUser.set(new CurrentPrincipal(teacherAId, UserRole.TEACHER));
        AiJobVO job = aiJobService.dispatch(courseAId, AiJobKind.QUESTION_ANNOTATE, "own-1",
                Map.of("items", List.of()));

        AiJobVO mine = aiJobService.getForOwner(job.getId());
        assertThat(mine.getId()).isEqualTo(job.getId());

        CurrentUser.set(new CurrentPrincipal(teacherBId, UserRole.TEACHER));
        assertThatThrownBy(() -> aiJobService.getForOwner(job.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("特性开关关闭 → 派发 503")
    void featureFlagOff_dispatch503() {
        featureFlags.setAiJobDispatchEnabled(false);
        CurrentUser.set(new CurrentPrincipal(teacherAId, UserRole.TEACHER));
        assertThatThrownBy(() -> aiJobService.dispatch(courseAId, AiJobKind.QUESTION_GENERATE, "off-1",
                Map.of("nodeCode", "KT01", "count", 1)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
    }

    private AiJob insertRunning(long ownerId, long courseId, String key, int attempts, LocalDateTime startedAt) {
        AiJob j = new AiJob();
        j.setKind(AiJobKind.QUESTION_GENERATE);
        j.setOwnerId(ownerId);
        j.setCourseId(courseId);
        j.setRequestKey(key);
        j.setStatus(AiJobStatus.RUNNING);
        j.setProgress(10);
        j.setAttempts(attempts);
        j.setRequestJson("{\"nodeCode\":\"KT01\",\"count\":1}");
        j.setCreatedAt(LocalDateTime.now().minusHours(1));
        j.setStartedAt(startedAt);
        aiJobMapper.insert(j);
        return j;
    }
}
