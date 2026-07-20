package com.wenjin.service.companion;

import com.wenjin.service.companion.ProbeSessionStore.ProbeOption;
import com.wenjin.service.companion.ProbeSessionStore.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProbeSessionStoreTest {

    private ProbeSessionStore store;

    @BeforeEach
    void setUp() {
        store = new ProbeSessionStore();
    }

    @Test
    @DisplayName("create BANK session and submit grades without shared ownership leak")
    void createAndSubmit() {
        var session = store.create(
                1L, 10L, "SE-1.1", 100L, "What is SE?", 1,
                List.of(new ProbeOption("A", "req"), new ProbeOption("B", "code")),
                "A", Source.BANK, 501L);

        assertThat(session.sessionId()).isNotBlank();
        assertThat(session.isEphemeral()).isFalse();
        assertThat(store.usedBankQuestionIds(1L, 10L, "SE-1.1")).containsExactly(501L);

        var submitted = store.submit(session.sessionId(), 1L, 10L, true);
        assertThat(submitted).isPresent();
        assertThat(submitted.get().submitted()).isTrue();
        assertThat(submitted.get().correct()).isTrue();

        // wrong owner
        assertThat(store.submit(session.sessionId(), 2L, 10L, false)).isEmpty();
        // idempotent
        assertThat(store.submit(session.sessionId(), 1L, 10L, false).get().correct()).isTrue();
    }

    @Test
    @DisplayName("EPHEMERAL does not require bankQuestionId")
    void ephemeral() {
        var session = store.create(
                1L, 10L, "SE-3.2", 200L, "temp stem", 4,
                List.of(), null, Source.EPHEMERAL, null);
        assertThat(session.isEphemeral()).isTrue();
        assertThat(store.usedBankQuestionIds(1L, 10L, "SE-3.2")).isEmpty();
    }

    @Test
    @DisplayName("BANK without bankQuestionId rejected")
    void bankRequiresId() {
        assertThatThrownBy(() -> store.create(
                1L, 10L, "SE-1.1", 1L, "s", 1, List.of(), "A", Source.BANK, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
