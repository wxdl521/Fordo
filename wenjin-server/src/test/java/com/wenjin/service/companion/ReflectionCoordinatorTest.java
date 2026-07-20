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
}
