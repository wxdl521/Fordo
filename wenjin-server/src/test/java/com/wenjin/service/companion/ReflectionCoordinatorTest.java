package com.wenjin.service.companion;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.ProbeStartVO;
import com.wenjin.dto.ProbeSubmitVO;
import com.wenjin.support.CompanionIntent;
import com.wenjin.support.Phase1Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReflectionCoordinatorTest {

    @Mock ProbeService probeService;

    private FeatureFlagsProperties flags;
    private ReflectionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        flags = new FeatureFlagsProperties();
        flags.setReflectionEnabled(true);
        coordinator = new ReflectionCoordinator(flags, probeService);
    }

    @Test
    @DisplayName("only EXPLAIN + flag on → auto probe")
    void shouldAutoProbe_explainOnly() {
        assertThat(coordinator.shouldAutoProbe(CompanionIntent.EXPLAIN)).isTrue();
        assertThat(coordinator.shouldAutoProbe(CompanionIntent.CHAT)).isFalse();
        assertThat(coordinator.shouldAutoProbe(CompanionIntent.QUERY)).isFalse();
        assertThat(coordinator.shouldAutoProbe(CompanionIntent.PRACTICE)).isFalse();

        flags.setReflectionEnabled(false);
        assertThat(coordinator.shouldAutoProbe(CompanionIntent.EXPLAIN)).isFalse();
    }

    @Test
    @DisplayName("maybeIssueProbe issues when EXPLAIN")
    void maybeIssue() {
        ProbeStartVO vo = new ProbeStartVO();
        vo.setSessionId("abc");
        when(probeService.startProbe(1L, 10L, "SE-1.1")).thenReturn(vo);

        Phase1Decision d = Phase1Decision.of(
                CompanionIntent.EXPLAIN, new CompanionAiClient.FinalText(""));
        Optional<ProbeStartVO> out = coordinator.maybeIssueProbe(1L, 10L, "SE-1.1", d);
        assertThat(out).contains(vo);
    }

    @Test
    @DisplayName("CHAT intent never starts probe")
    void chatNoProbe() {
        Phase1Decision d = Phase1Decision.chat(new CompanionAiClient.FinalText("hi"));
        assertThat(coordinator.maybeIssueProbe(1L, 10L, "SE-1.1", d)).isEmpty();
        verify(probeService, never()).startProbe(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("incorrect submit → next strategy")
    void nextStrategyOnIncorrect() {
        ProbeSubmitVO sub = new ProbeSubmitVO();
        sub.setCorrect(false);
        sub.setReexplainAvailable(true);
        assertThat(coordinator.nextStrategyOnIncorrect(
                sub, EnumSet.of(StrategySwitcher.Strategy.ANALOGY), EnumSet.noneOf(StrategySwitcher.Strategy.class)))
                .contains(StrategySwitcher.Strategy.EXAMPLE);
    }

    // ── 换策略接入真实链路（T7） ──

    private ProbeOutcomeStore wireOutcomeStore() {
        ProbeOutcomeStore store = new ProbeOutcomeStore();
        coordinator = new ReflectionCoordinator(flags, probeService, store);
        return store;
    }

    @Test
    @DisplayName("无探针记录 / 未接 store → 不注入")
    void directive_noOutcome_empty() {
        assertThat(coordinator.resolveStrategyDirective(1L, 10L, "SE-1.1")).isEmpty();
        wireOutcomeStore();
        assertThat(coordinator.resolveStrategyDirective(1L, 10L, "SE-1.1")).isEmpty();
    }

    @Test
    @DisplayName("30 分钟内未通过 → 依次选 ANALOGY 再 EXAMPLE；同一次失败只换一次；前一策略记为无效")
    void directive_failedProbe_switchesOncePerFailure() {
        ProbeOutcomeStore store = wireOutcomeStore();
        com.wenjin.service.memory.StudentMemoryService memory =
                org.mockito.Mockito.mock(com.wenjin.service.memory.StudentMemoryService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(coordinator, "memoryService", memory);

        store.recordProbeResult(1L, 10L, "SE-1.1", false);
        ReflectionCoordinator.StrategyDirective first =
                coordinator.resolveStrategyDirective(1L, 10L, null).orElseThrow();
        assertThat(first.nodeCode()).isEqualTo("SE-1.1");
        assertThat(first.strategy()).isEqualTo(StrategySwitcher.Strategy.ANALOGY);
        assertThat(first.previousStrategy()).isNull();
        // same failure already consumed
        assertThat(coordinator.resolveStrategyDirective(1L, 10L, null)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(memory);

        // re-explained with ANALOGY, probe fails again → EXAMPLE; ANALOGY recorded as ineffective
        store.recordProbeResult(1L, 10L, "SE-1.1", false);
        ReflectionCoordinator.StrategyDirective second =
                coordinator.resolveStrategyDirective(1L, 10L, "SE-1.1").orElseThrow();
        assertThat(second.strategy()).isEqualTo(StrategySwitcher.Strategy.EXAMPLE);
        assertThat(second.previousStrategy()).isEqualTo(StrategySwitcher.Strategy.ANALOGY);
        verify(memory).upsertOrReinforce(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.startsWith("[ANALOGY][无效]"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("探针通过 → 不注入，生效策略记为有效（只记一次）")
    void directive_correctProbe_recordsEffectiveOnce() {
        ProbeOutcomeStore store = wireOutcomeStore();
        com.wenjin.service.memory.StudentMemoryService memory =
                org.mockito.Mockito.mock(com.wenjin.service.memory.StudentMemoryService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(coordinator, "memoryService", memory);
        store.recordProbeResult(1L, 10L, "SE-1.1", false);
        coordinator.resolveStrategyDirective(1L, 10L, null);
        store.recordProbeResult(1L, 10L, "SE-1.1", true);

        assertThat(coordinator.resolveStrategyDirective(1L, 10L, null)).isEmpty();
        assertThat(coordinator.resolveStrategyDirective(1L, 10L, null)).isEmpty();
        verify(memory, org.mockito.Mockito.times(1)).upsertOrReinforce(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.startsWith("[ANALOGY][有效]"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("五种策略都试过 → 不再注入")
    void directive_exhausted_empty() {
        ProbeOutcomeStore store = wireOutcomeStore();
        for (int i = 0; i < StrategySwitcher.ORDER.size(); i++) {
            store.recordProbeResult(1L, 10L, "SE-1.1", false);
            assertThat(coordinator.resolveStrategyDirective(1L, 10L, null)).isPresent();
        }
        store.recordProbeResult(1L, 10L, "SE-1.1", false);
        assertThat(coordinator.resolveStrategyDirective(1L, 10L, null)).isEmpty();
    }

    @Test
    @DisplayName("kill switch：reflection-enabled=false → 不注入、不改状态")
    void directive_killSwitch() {
        ProbeOutcomeStore store = wireOutcomeStore();
        store.recordProbeResult(1L, 10L, "SE-1.1", false);
        flags.setReflectionEnabled(false);

        assertThat(coordinator.resolveStrategyDirective(1L, 10L, null)).isEmpty();
        assertThat(store.latest(1L, 10L, null).orElseThrow().switchConsumed()).isFalse();
    }
}
