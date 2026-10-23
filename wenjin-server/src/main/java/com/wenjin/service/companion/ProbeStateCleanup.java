package com.wenjin.service.companion;

import com.wenjin.config.LimitsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 定时清理过期的理解探针状态（V26 {@code probe_session} / {@code probe_outcome}）。
 * 保留期 = {@code wenjin.limits.reflection.probe-retention-days}（且不小于会话 TTL）；
 * 会话 TTL 本身（过期读作「不存在」）由 {@link ProbeSessionStore} 在读取时判定，不依赖本任务及时运行。
 */
@Component
public class ProbeStateCleanup {

    private static final Logger log = LoggerFactory.getLogger(ProbeStateCleanup.class);

    private final ProbeSessionStore sessions;
    private final ProbeOutcomeStore outcomes;
    private final LimitsProperties limits;
    private final Clock clock;

    public ProbeStateCleanup(ProbeSessionStore sessions, ProbeOutcomeStore outcomes,
                             LimitsProperties limits, Clock clock) {
        this.sessions = sessions;
        this.outcomes = outcomes;
        this.limits = limits;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${wenjin.limits.reflection.cleanup-interval-ms:3600000}",
            initialDelayString = "${wenjin.limits.reflection.cleanup-interval-ms:3600000}")
    public void cleanup() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.warn("probe state cleanup failed: {}", e.getMessage());
        }
    }

    /** @return {sessionsDeleted, outcomesDeleted} */
    int[] purge() {
        LimitsProperties.Reflection cfg = limits.getReflection();
        Duration retention = Duration.ofDays(Math.max(1, cfg.getProbeRetentionDays()));
        Duration ttl = Duration.ofHours(Math.max(1, cfg.getProbeSessionTtlHours()));
        if (retention.compareTo(ttl) < 0) {
            retention = ttl;
        }
        Instant cutoff = clock.instant().minus(retention);
        int s = sessions.purgeCreatedBefore(cutoff);
        int o = outcomes.purgeBefore(cutoff);
        if (s > 0 || o > 0) {
            log.info("probe state cleanup: sessions={} outcomes={} cutoff={}", s, o, cutoff);
        }
        return new int[]{s, o};
    }
}
