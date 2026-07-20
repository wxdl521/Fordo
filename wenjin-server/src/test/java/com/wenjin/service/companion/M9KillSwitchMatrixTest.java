package com.wenjin.service.companion;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.support.CompanionIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Combinatorial smoke for M9 kill switches (reflection + companion LLM).
 */
class M9KillSwitchMatrixTest {

    @ParameterizedTest(name = "reflection={0} llm={1} explain={2} → autoProbe={3} degradeOnDisable={4}")
    @CsvSource({
            // reflection, companionLlm, isExplain, expectAutoProbe, expectDegradeWhenLlmOff
            "true,  true,  true,  true,  false",
            "true,  true,  false, false, false",
            "false, true,  true,  false, false",
            "false, true,  false, false, false",
            "true,  false, true,  true,  true",
            "true,  false, false, false, true",
            "false, false, true,  false, true",
            "false, false, false, false, true",
    })
    @DisplayName("kill-switch matrix")
    void matrix(boolean reflection,
                boolean companionLlm,
                boolean explain,
                boolean expectAutoProbe,
                boolean expectDegradeWhenLlmOff) {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setReflectionEnabled(reflection);
        flags.setCompanionLlmEnabled(companionLlm);

        ReflectionCoordinator coordinator = new ReflectionCoordinator(flags, mock(ProbeService.class));
        CompanionIntent intent = explain ? CompanionIntent.EXPLAIN : CompanionIntent.CHAT;

        assertThat(coordinator.shouldAutoProbe(intent)).isEqualTo(expectAutoProbe);
        // When companion LLM off, orchestration must take degrade path (flag surface)
        assertThat(!flags.isCompanionLlmEnabled()).isEqualTo(expectDegradeWhenLlmOff);
        // Reflection tool surface
        assertThat(flags.isReflectionEnabled()).isEqualTo(reflection);
    }
}
