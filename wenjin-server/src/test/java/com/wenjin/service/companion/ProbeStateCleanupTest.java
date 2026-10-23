package com.wenjin.service.companion;

import com.wenjin.config.LimitsProperties;
import com.wenjin.service.companion.ProbeSessionStore.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProbeStateCleanupTest {

    private final ProbeTestClock clock = new ProbeTestClock(Instant.parse("2026-10-23T08:00:00Z"));
    private final InMemoryProbeSessionRepository sessionRepo = new InMemoryProbeSessionRepository();
    private final InMemoryProbeOutcomeRepository outcomeRepo = new InMemoryProbeOutcomeRepository();
    private final LimitsProperties limits = new LimitsProperties();
    private final ProbeSessionStore sessions = new ProbeSessionStore(sessionRepo, clock, () -> Duration.ofHours(24));
    private final ProbeOutcomeStore outcomes = new ProbeOutcomeStore(outcomeRepo, clock);
    private final ProbeStateCleanup cleanup = new ProbeStateCleanup(sessions, outcomes, limits, clock);

    private void seed() {
        sessions.create(1L, 10L, "A", 1L, "s", 4, List.of(), null, Source.EPHEMERAL, null);
        outcomes.recordProbeResult(1L, 10L, "A", false);
    }

    @Test
    @DisplayName("保留期内不删；超过保留天数后会话与结果行都被清理")
    void purgesPastRetention() {
        seed();
        clock.advance(Duration.ofDays(13));
        assertThat(cleanup.purge()).containsExactly(0, 0);

        clock.advance(Duration.ofDays(2));
        assertThat(cleanup.purge()).containsExactly(1, 1);
        assertThat(sessions.size()).isZero();
        assertThat(outcomes.size()).isZero();
    }

    @Test
    @DisplayName("保留期不得小于会话 TTL（配置错误时按 TTL 兜底）")
    void retentionNeverBelowTtl() {
        limits.getReflection().setProbeRetentionDays(1);
        limits.getReflection().setProbeSessionTtlHours(72);
        seed();
        clock.advance(Duration.ofHours(48));
        assertThat(cleanup.purge()).containsExactly(0, 0);
        clock.advance(Duration.ofHours(25));
        assertThat(cleanup.purge()).containsExactly(1, 1);
    }

    @Test
    @DisplayName("定时入口吞掉异常，不让调度线程崩")
    void cleanupSwallowsFailures() {
        ProbeSessionStore broken = new ProbeSessionStore(new InMemoryProbeSessionRepository() {
            @Override
            public int deleteCreatedBefore(Instant cutoff) {
                throw new IllegalStateException("db down");
            }
        }, clock, () -> Duration.ofHours(24));
        new ProbeStateCleanup(broken, outcomes, limits, clock).cleanup();
    }
}
