package com.wenjin.service.companion;

import com.wenjin.service.companion.ProbeSessionStore.ProbeOption;
import com.wenjin.service.companion.ProbeSessionStore.ProbeSession;
import com.wenjin.service.companion.ProbeSessionStore.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 持久化语义：同一 repository 被两个 store 实例共享 = 模拟进程重启后数据仍在；TTL / 幂等 / 归属 / 去重 / 清理。
 */
class ProbeSessionPersistenceTest {

    private final ProbeTestClock clock = new ProbeTestClock(Instant.parse("2026-10-23T08:00:00Z"));
    private InMemoryProbeSessionRepository repo;
    private ProbeSessionStore store;

    @BeforeEach
    void setUp() {
        repo = new InMemoryProbeSessionRepository();
        store = newStore(Duration.ofHours(24));
    }

    private ProbeSessionStore newStore(Duration ttl) {
        return new ProbeSessionStore(repo, clock, () -> ttl);
    }

    private ProbeSession bank(long student, String node, long bankId) {
        return store.create(student, 10L, node, 100L, "stem", 1,
                List.of(new ProbeOption("A", "x"), new ProbeOption("B", "y")), "A", Source.BANK, bankId);
    }

    @Test
    @DisplayName("重启后（新 store 同一数据）仍能 find / submit，选项与答案完整")
    void surviveRestart() {
        ProbeSession s = bank(1L, "SE-1.1", 501L);

        ProbeSessionStore restarted = newStore(Duration.ofHours(24));
        Optional<ProbeSession> found = restarted.find(s.sessionId());
        assertThat(found).isPresent();
        assertThat(found.get().options()).containsExactly(new ProbeOption("A", "x"), new ProbeOption("B", "y"));
        assertThat(found.get().correctKey()).isEqualTo("A");
        assertThat(found.get().submitted()).isFalse();

        assertThat(restarted.submit(s.sessionId(), 1L, 10L, true)).get().extracting(ProbeSession::correct).isEqualTo(true);
        // and the pre-restart instance sees the stored result
        assertThat(store.find(s.sessionId())).get().extracting(ProbeSession::submitted).isEqualTo(true);
    }

    @Test
    @DisplayName("幂等：二次提交返回首次判分，不重判；错误归属 / 错误课程 / 未知 id 返回 empty")
    void idempotentAndOwnership() {
        ProbeSession s = bank(1L, "SE-1.1", 501L);

        assertThat(store.submit(s.sessionId(), 2L, 10L, true)).isEmpty();
        assertThat(store.submit(s.sessionId(), 1L, 11L, true)).isEmpty();
        assertThat(store.submit("nope", 1L, 10L, true)).isEmpty();
        assertThat(store.find(s.sessionId())).get().extracting(ProbeSession::submitted).isEqualTo(false);

        assertThat(store.submit(s.sessionId(), 1L, 10L, false).orElseThrow().correct()).isFalse();
        assertThat(store.submit(s.sessionId(), 1L, 10L, true).orElseThrow().correct()).isFalse();
        // a "restarted" store likewise returns the first grade
        assertThat(newStore(Duration.ofHours(24)).submit(s.sessionId(), 1L, 10L, true).orElseThrow().correct()).isFalse();
    }

    @Test
    @DisplayName("TTL：超过有效期视为不存在（find / submit 均 empty），未过期仍可用；TTL 变更即时生效")
    void ttlExpiry() {
        ProbeSession s = bank(1L, "SE-1.1", 501L);

        clock.advance(Duration.ofHours(23));
        assertThat(store.find(s.sessionId())).isPresent();

        clock.advance(Duration.ofHours(2));
        assertThat(store.find(s.sessionId())).isEmpty();
        assertThat(store.submit(s.sessionId(), 1L, 10L, true)).isEmpty();

        // a longer TTL on a "restarted" store revives it (row is still there until cleanup)
        assertThat(newStore(Duration.ofHours(48)).find(s.sessionId())).isPresent();
    }

    @Test
    @DisplayName("题库题去重：重启后仍排除已发过的题；按 学生×课程×节点 隔离；EPHEMERAL 不占用")
    void usedBankIdsSurviveRestart() {
        bank(1L, "SE-1.1", 501L);
        bank(1L, "SE-1.1", 502L);
        bank(1L, "SE-3.2", 600L);
        bank(2L, "SE-1.1", 700L);
        store.create(1L, 10L, "SE-1.1", 100L, "temp", 4, List.of(), null, Source.EPHEMERAL, null);

        ProbeSessionStore restarted = newStore(Duration.ofHours(24));
        assertThat(restarted.usedBankQuestionIds(1L, 10L, "SE-1.1")).containsExactlyInAnyOrder(501L, 502L);
        assertThat(restarted.usedBankQuestionIds(1L, 10L, "SE-3.2")).containsExactly(600L);
        assertThat(restarted.usedBankQuestionIds(1L, 11L, "SE-1.1")).isEmpty();
        assertThat(restarted.usedBankQuestionIds(2L, 10L, "SE-1.1")).containsExactly(700L);
    }

    @Test
    @DisplayName("TTL 过期的会话仍计入去重（直到被清理删除），避免刚过期就重复出同一道题")
    void expiredStillCountsAsUsedUntilPurged() {
        bank(1L, "SE-1.1", 501L);
        clock.advance(Duration.ofHours(30));
        assertThat(store.usedBankQuestionIds(1L, 10L, "SE-1.1")).containsExactly(501L);

        assertThat(store.purgeCreatedBefore(clock.instant().minus(Duration.ofHours(24)))).isEqualTo(1);
        assertThat(store.usedBankQuestionIds(1L, 10L, "SE-1.1")).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("purge 只删早于 cutoff 的会话")
    void purgeKeepsFresh() {
        bank(1L, "SE-1.1", 501L);
        clock.advance(Duration.ofDays(10));
        ProbeSession fresh = bank(1L, "SE-1.1", 502L);

        assertThat(store.purgeCreatedBefore(clock.instant().minus(Duration.ofDays(7)))).isEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.find(fresh.sessionId())).isPresent();
    }

    @Test
    @DisplayName("EWMA 隔离：存储层不依赖任何掌握度 / 作答记录组件")
    void storesDoNotDependOnMasteryOrAnswers() {
        for (Class<?> c : List.of(ProbeSessionStore.class, ProbeOutcomeStore.class,
                MybatisProbeSessionRepository.class, MybatisProbeOutcomeRepository.class)) {
            for (var ctor : c.getConstructors()) {
                for (Class<?> p : ctor.getParameterTypes()) {
                    assertThat(p.getSimpleName().toLowerCase())
                            .as("%s ctor param", c.getSimpleName())
                            .doesNotContain("mastery", "answerrecord", "practice");
                }
            }
        }
    }
}
