package com.wenjin.eval.integration;

import com.wenjin.dto.ProbeStartVO;
import com.wenjin.dto.ProbeSubmitRequest;
import com.wenjin.mapper.LearningDataResetMapper;
import com.wenjin.mapper.ProbeOutcomeMapper;
import com.wenjin.mapper.ProbeSessionMapper;
import com.wenjin.service.companion.MybatisProbeOutcomeRepository;
import com.wenjin.service.companion.MybatisProbeSessionRepository;
import com.wenjin.service.companion.ProbeOutcomeStore;
import com.wenjin.service.companion.ProbeService;
import com.wenjin.service.companion.ProbeSessionStore;
import com.wenjin.service.companion.ProbeSessionStore.ProbeOption;
import com.wenjin.service.companion.ProbeSessionStore.ProbeSession;
import com.wenjin.service.companion.ProbeSessionStore.Source;
import com.wenjin.service.companion.ProbeStateCleanup;
import com.wenjin.service.companion.StrategySwitcher.Strategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real MySQL (wenjin_eval) round trip of V26 {@code probe_session} / {@code probe_outcome}: the production SQL
 * of the MyBatis repositories, the "restart" (fresh store instances over the same rows), TTL, purge, the
 * admin course-reset statements, and the EWMA-isolation invariant. All rows use synthetic ids and are removed
 * in {@link #cleanUp()}.
 * <p>Gated by {@code WENJIN_EVAL_IT=true}; DB = Testcontainers or {@code -Dwenjin.eval.jdbc-url}.
 */
@EnabledIfEnvironmentVariable(named = "WENJIN_EVAL_IT", matches = "true")
class ProbeStatePersistenceIT extends EvalDbSupport {

    private static final long STUDENT = 9_100_001L;
    private static final long COURSE = 9_100_001L;
    private static final long OTHER_COURSE = 9_100_002L;

    @Autowired ProbeSessionStore sessions;
    @Autowired ProbeOutcomeStore outcomes;
    @Autowired ProbeService probeService;
    @Autowired ProbeStateCleanup cleanup;
    @Autowired ProbeSessionMapper sessionMapper;
    @Autowired ProbeOutcomeMapper outcomeMapper;
    @Autowired LearningDataResetMapper resetMapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM probe_session WHERE student_id = ?", STUDENT);
        jdbc.update("DELETE FROM probe_outcome WHERE student_id = ?", STUDENT);
    }

    private ProbeSessionStore restartedSessions(Duration ttl) {
        return new ProbeSessionStore(new MybatisProbeSessionRepository(sessionMapper), Clock.systemUTC(), () -> ttl);
    }

    private ProbeOutcomeStore restartedOutcomes() {
        return new ProbeOutcomeStore(new MybatisProbeOutcomeRepository(outcomeMapper), Clock.systemUTC());
    }

    private ProbeSession bank(long course, String node, long bankId) {
        return sessions.create(STUDENT, course, node, 100L, "题干「含中文」?", 1,
                List.of(new ProbeOption("A", "选项甲"), new ProbeOption("B", "选项乙")), "A", Source.BANK, bankId);
    }

    @Test
    @DisplayName("会话：落库后新 store 可读（选项/答案/来源完整）；提交幂等不重判；归属与 TTL 生效")
    void sessionRoundTrip() {
        ProbeSession s = bank(COURSE, "NODE-A", 7001L);
        assertThat(s.sessionId()).hasSize(32);

        ProbeSessionStore restarted = restartedSessions(Duration.ofHours(24));
        ProbeSession loaded = restarted.find(s.sessionId()).orElseThrow();
        assertThat(loaded.options()).containsExactly(new ProbeOption("A", "选项甲"), new ProbeOption("B", "选项乙"));
        assertThat(loaded.correctKey()).isEqualTo("A");
        assertThat(loaded.source()).isEqualTo(Source.BANK);
        assertThat(loaded.bankQuestionId()).isEqualTo(7001L);
        assertThat(loaded.stem()).isEqualTo("题干「含中文」?");
        assertThat(loaded.submitted()).isFalse();
        assertThat(loaded.correct()).isNull();
        assertThat(loaded.createdAt()).isBetween(Instant.now().minusSeconds(30), Instant.now().plusSeconds(30));

        assertThat(restarted.submit(s.sessionId(), STUDENT + 1, COURSE, true)).isEmpty();
        assertThat(restarted.submit(s.sessionId(), STUDENT, COURSE + 1, true)).isEmpty();
        assertThat(restarted.submit(s.sessionId(), STUDENT, COURSE, false).orElseThrow().correct()).isFalse();
        assertThat(sessions.submit(s.sessionId(), STUDENT, COURSE, true).orElseThrow().correct()).isFalse();
        assertThat(jdbc.queryForObject("SELECT submitted_at IS NOT NULL FROM probe_session WHERE session_id = ?",
                Boolean.class, s.sessionId())).isTrue();

        // TTL: backdate created_at past 24h → not found, though the row remains
        jdbc.update("UPDATE probe_session SET created_at = DATE_SUB(NOW(), INTERVAL 30 HOUR) WHERE session_id = ?", s.sessionId());
        assertThat(sessions.find(s.sessionId())).isEmpty();
        assertThat(restartedSessions(Duration.ofHours(48)).find(s.sessionId())).isPresent();
    }

    @Test
    @DisplayName("EPHEMERAL 会话：answer 不丢、空选项 round-trip 为空列表")
    void ephemeralRoundTrip() {
        ProbeSession s = sessions.create(STUDENT, COURSE, "NODE-A", null, "临时题", 4, List.of(), null, Source.EPHEMERAL, null);
        ProbeSession loaded = restartedSessions(Duration.ofHours(24)).find(s.sessionId()).orElseThrow();
        assertThat(loaded.isEphemeral()).isTrue();
        assertThat(loaded.options()).isEmpty();
        assertThat(loaded.bankQuestionId()).isNull();
        assertThat(loaded.nodeId()).isNull();
    }

    @Test
    @DisplayName("题库题去重：按 学生×课程×节点 查已用 id，不含 EPHEMERAL")
    void usedBankIds() {
        bank(COURSE, "NODE-A", 7001L);
        bank(COURSE, "NODE-A", 7002L);
        bank(COURSE, "NODE-B", 7003L);
        bank(OTHER_COURSE, "NODE-A", 7004L);
        sessions.create(STUDENT, COURSE, "NODE-A", null, "temp", 4, List.of(), null, Source.EPHEMERAL, null);

        assertThat(restartedSessions(Duration.ofHours(24)).usedBankQuestionIds(STUDENT, COURSE, "NODE-A"))
                .containsExactlyInAnyOrder(7001L, 7002L);
        assertThat(sessions.usedBankQuestionIds(STUDENT, COURSE, "NODE-B")).containsExactly(7003L);
        assertThat(sessions.usedBankQuestionIds(STUDENT, OTHER_COURSE, "NODE-A")).containsExactly(7004L);
        assertThat(sessions.usedBankQuestionIds(STUDENT + 1, COURSE, "NODE-A")).isEmpty();
    }

    @Test
    @DisplayName("结果：整行 upsert（唯一键覆盖）、策略/已尝试集合/标记位 round-trip；latest 焦点优先否则最近")
    void outcomeRoundTrip() {
        outcomes.recordProbeResult(STUDENT, COURSE, "NODE-A", false);
        outcomes.markStrategySwitched(STUDENT, COURSE, "NODE-A", Strategy.ANALOGY);
        outcomes.markStrategySwitched(STUDENT, COURSE, "NODE-A", Strategy.EXAMPLE);

        ProbeOutcomeStore restarted = restartedOutcomes();
        ProbeOutcomeStore.NodeOutcome o = restarted.latest(STUDENT, COURSE, "NODE-A").orElseThrow();
        assertThat(o.correct()).isFalse();
        assertThat(o.switchConsumed()).isTrue();
        assertThat(o.activeStrategy()).isEqualTo(Strategy.EXAMPLE);
        assertThat(o.tried()).containsExactlyInAnyOrder(Strategy.ANALOGY, Strategy.EXAMPLE);
        assertThat(o.outcomeRecorded()).isFalse();
        assertThat(restarted.isWithinWindow(o)).isTrue();

        // next probe on the same node overwrites the single row and carries the active strategy forward
        restarted.recordProbeResult(STUDENT, COURSE, "NODE-A", false);
        ProbeOutcomeStore.NodeOutcome again = restarted.latest(STUDENT, COURSE, "NODE-A").orElseThrow();
        assertThat(again.switchConsumed()).isFalse();
        assertThat(again.strategyUsed()).isEqualTo(Strategy.EXAMPLE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM probe_outcome WHERE student_id = ? AND course_id = ?",
                Integer.class, STUDENT, COURSE)).isEqualTo(1);

        restarted.recordProbeResult(STUDENT, COURSE, "NODE-A", true);
        restarted.markOutcomeRecorded(STUDENT, COURSE, "NODE-A");
        ProbeOutcomeStore.NodeOutcome passed = outcomes.latest(STUDENT, COURSE, "NODE-A").orElseThrow();
        assertThat(passed.correct()).isTrue();
        assertThat(passed.tried()).isEmpty();
        assertThat(passed.outcomeRecorded()).isTrue();

        // focus miss falls back to the most recent in the course
        restarted.recordProbeResult(STUDENT, COURSE, "NODE-B", false);
        jdbc.update("UPDATE probe_outcome SET updated_at = DATE_SUB(NOW(3), INTERVAL 1 HOUR) WHERE student_id = ? AND node_code = 'NODE-A'", STUDENT);
        assertThat(outcomes.latest(STUDENT, COURSE, "NODE-Z").orElseThrow().nodeCode()).isEqualTo("NODE-B");
        assertThat(outcomes.latest(STUDENT, COURSE, "NODE-A").orElseThrow().nodeCode()).isEqualTo("NODE-A");
        assertThat(outcomes.latest(STUDENT, COURSE, "NODE-A").map(outcomes::isWithinWindow)).contains(false);
    }

    @Test
    @DisplayName("清理：只删早于保留期的会话与结果行")
    void purge() {
        ProbeSession old = bank(COURSE, "NODE-A", 7001L);
        ProbeSession fresh = bank(COURSE, "NODE-A", 7002L);
        outcomes.recordProbeResult(STUDENT, COURSE, "NODE-A", false);
        outcomes.recordProbeResult(STUDENT, COURSE, "NODE-B", false);
        jdbc.update("UPDATE probe_session SET created_at = DATE_SUB(NOW(), INTERVAL 20 DAY) WHERE session_id = ?", old.sessionId());
        jdbc.update("UPDATE probe_outcome SET updated_at = DATE_SUB(NOW(3), INTERVAL 20 DAY) WHERE student_id = ? AND node_code = 'NODE-A'", STUDENT);

        // other rows from other tests/students may also be purged by the real job; assert on ours only
        cleanup.cleanup();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM probe_session WHERE session_id = ?", Integer.class, old.sessionId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM probe_session WHERE session_id = ?", Integer.class, fresh.sessionId())).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM probe_outcome WHERE student_id = ?", Integer.class, STUDENT)).isOne();
        assertThat(outcomes.latest(STUDENT, COURSE, "NODE-B")).isPresent();
    }

    @Test
    @DisplayName("管理后台重置：按 course_id 清空两张探针表，不碰其它课程")
    void adminResetStatements() {
        bank(COURSE, "NODE-A", 7001L);
        bank(OTHER_COURSE, "NODE-A", 7002L);
        outcomes.recordProbeResult(STUDENT, COURSE, "NODE-A", false);
        outcomes.recordProbeResult(STUDENT, OTHER_COURSE, "NODE-A", false);

        assertThat(resetMapper.deleteProbeSessions(COURSE)).isEqualTo(1);
        assertThat(resetMapper.deleteProbeOutcomes(COURSE)).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM probe_session WHERE student_id = ?", Integer.class, STUDENT)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM probe_session WHERE course_id = ?", Integer.class, COURSE)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM probe_outcome WHERE student_id = ?", Integer.class, STUDENT)).isOne();
        assertThat(resetMapper.deleteProbeSessions(COURSE)).isZero();
    }

    @Test
    @DisplayName("EWMA 隔离：真实 ProbeService 发题 + 提交后 student_mastery / answer_record / mastery_snapshot 行数不变")
    void probesNeverTouchMastery() {
        List<Map<String, Object>> nodes = jdbc.queryForList(
                "SELECT course_id, node_code FROM kg_node WHERE archived_at IS NULL LIMIT 1");
        Assumptions.assumeFalse(nodes.isEmpty(), "eval fixture has no kg_node");
        long course = ((Number) nodes.get(0).get("course_id")).longValue();
        String node = (String) nodes.get(0).get("node_code");

        long mastery = count("student_mastery");
        long answers = count("answer_record");
        long snaps = count("mastery_snapshot");
        long practice = count("practice_session");

        ProbeStartVO start = probeService.startProbe(STUDENT, course, node);
        ProbeSubmitRequest req = new ProbeSubmitRequest();
        req.setStudentId(STUDENT);
        req.setCourseId(course);
        req.setStudentAnswer("A");
        probeService.submit(start.getSessionId(), req);
        probeService.submit(start.getSessionId(), req); // idempotent resubmit

        assertThat(count("student_mastery")).isEqualTo(mastery);
        assertThat(count("answer_record")).isEqualTo(answers);
        assertThat(count("mastery_snapshot")).isEqualTo(snaps);
        assertThat(count("practice_session")).isEqualTo(practice);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM probe_session WHERE session_id = ? AND submitted_at IS NOT NULL",
                Integer.class, start.getSessionId())).isOne();
        assertThat(outcomes.latest(STUDENT, course, node)).isPresent();
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }
}
