package com.wenjin.service.companion;

import com.wenjin.service.companion.StrategySwitcher.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProbeOutcomeStoreTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-21T08:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final ProbeOutcomeStore store = new ProbeOutcomeStore(clock);

    @Test
    @DisplayName("latest: 焦点节点有记录优先，否则取该学生本课最近一次探针")
    void latestPrefersFocusThenMostRecent() {
        store.recordProbeResult(1L, 10L, "A", false);
        now.set(now.get().plusSeconds(60));
        store.recordProbeResult(1L, 10L, "B", true);
        store.recordProbeResult(2L, 10L, "C", false); // other student

        assertThat(store.latest(1L, 10L, "A")).get().extracting(ProbeOutcomeStore.NodeOutcome::nodeCode).isEqualTo("A");
        assertThat(store.latest(1L, 10L, null)).get().extracting(ProbeOutcomeStore.NodeOutcome::nodeCode).isEqualTo("B");
        assertThat(store.latest(1L, 10L, "UNPROBED")).get().extracting(ProbeOutcomeStore.NodeOutcome::nodeCode).isEqualTo("B");
        assertThat(store.latest(1L, 11L, null)).isEmpty();
    }

    @Test
    @DisplayName("窗口：30 分钟内为最近；之后不再算")
    void withinWindow() {
        store.recordProbeResult(1L, 10L, "A", false);
        ProbeOutcomeStore.NodeOutcome o = store.latest(1L, 10L, "A").orElseThrow();
        now.set(now.get().plus(Duration.ofMinutes(29)));
        assertThat(store.isWithinWindow(o)).isTrue();
        now.set(now.get().plus(Duration.ofMinutes(2)));
        assertThat(store.isWithinWindow(o)).isFalse();
    }

    @Test
    @DisplayName("换策略后：标记已消费、记录已尝试；下一次探针结果带上当时生效的策略")
    void switchThenNextProbeCarriesStrategy() {
        store.recordProbeResult(1L, 10L, "A", false);
        store.markStrategySwitched(1L, 10L, "A", Strategy.ANALOGY);

        ProbeOutcomeStore.NodeOutcome switched = store.latest(1L, 10L, "A").orElseThrow();
        assertThat(switched.switchConsumed()).isTrue();
        assertThat(switched.tried()).containsExactly(Strategy.ANALOGY);
        assertThat(switched.activeStrategy()).isEqualTo(Strategy.ANALOGY);

        store.recordProbeResult(1L, 10L, "A", false);
        ProbeOutcomeStore.NodeOutcome again = store.latest(1L, 10L, "A").orElseThrow();
        assertThat(again.switchConsumed()).isFalse();
        assertThat(again.strategyUsed()).isEqualTo(Strategy.ANALOGY);
        assertThat(again.tried()).containsExactly(Strategy.ANALOGY);
    }

    @Test
    @DisplayName("探针通过：清空已尝试集合（下次失败从头选），但保留生效策略供记录有效")
    void correctResetsTried() {
        store.recordProbeResult(1L, 10L, "A", false);
        store.markStrategySwitched(1L, 10L, "A", Strategy.ANALOGY);
        store.recordProbeResult(1L, 10L, "A", true);

        ProbeOutcomeStore.NodeOutcome o = store.latest(1L, 10L, "A").orElseThrow();
        assertThat(o.correct()).isTrue();
        assertThat(o.strategyUsed()).isEqualTo(Strategy.ANALOGY);
        assertThat(o.tried()).isEmpty();

        store.markOutcomeRecorded(1L, 10L, "A");
        assertThat(store.latest(1L, 10L, "A").orElseThrow().outcomeRecorded()).isTrue();
    }

    @Test
    @DisplayName("非法入参忽略")
    void ignoresBadInput() {
        store.recordProbeResult(null, 10L, "A", false);
        store.recordProbeResult(1L, 10L, " ", false);
        assertThat(store.latest(1L, 10L, null)).isEmpty();
        assertThat(store.size()).isZero();
    }
}
