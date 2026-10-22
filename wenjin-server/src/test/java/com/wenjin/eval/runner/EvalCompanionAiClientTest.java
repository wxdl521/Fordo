package com.wenjin.eval.runner;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.ai.CompanionAiClient.StreamTurnResult;
import com.wenjin.common.BusinessException;
import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.golden.GoldenFile.GoldenRound;
import com.wenjin.eval.golden.GoldenFile.GoldenTurn;
import com.wenjin.eval.replay.ReplayCompanionAiClient;
import com.wenjin.eval.scenario.EvalScenarioDocument.LiveFaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalCompanionAiClientTest {

    private static final class CountingClient implements CompanionAiClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public StreamTurnResult streamTurn(List<Map<String, Object>> m, List<Map<String, Object>> s,
                                           ToolChoice c, Consumer<String> d) {
            calls.incrementAndGet();
            d.accept("live");
            return new StreamTurnResult("live", List.of(), null, "stop");
        }

        @Override
        public void stream(String sp, List<ChatMsg> h, String u, Consumer<String> t) {
            calls.incrementAndGet();
            t.accept("legacy");
        }

        @Override
        public TurnOutcome completeWithTools(List<Map<String, Object>> m, List<Map<String, Object>> s) {
            calls.incrementAndGet();
            return new FinalText("x");
        }

        @Override
        public void streamMessages(List<Map<String, Object>> m, Consumer<String> t) {
            calls.incrementAndGet();
        }
    }

    private static StreamTurnResult call(CompanionAiClient client) {
        return client.streamTurn(List.of(), List.of(), CompanionAiClient.ToolChoice.AUTO, t -> {});
    }

    @Test
    @DisplayName("fault injection: first N calls (any method) fail as AI_ERROR without 'timeout', then delegate")
    void faults_failFirstN() {
        CountingClient base = new CountingClient();
        FaultInjectingCompanionAiClient faults = new FaultInjectingCompanionAiClient(base, 2);

        assertThatThrownBy(() -> call(faults)).isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("timeout");
        assertThatThrownBy(() -> faults.stream("s", List.of(), "u", t -> {})).isInstanceOf(BusinessException.class);
        assertThat(call(faults).content()).isEqualTo("live");
        assertThat(faults.injected()).isEqualTo(2);
        assertThat(base.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("scenario layers: replay source, record inside faults, clear restores base")
    void scenarioLayers() {
        CountingClient base = new CountingClient();
        EvalCompanionAiClient client = new EvalCompanionAiClient(base);
        assertThat(call(client).content()).isEqualTo("live");

        ReplayCompanionAiClient replay = new ReplayCompanionAiClient(new GoldenFile("S07", 2, "", "", true,
                List.of(new GoldenTurn(List.of(new GoldenRound("replayed", List.of(), null))))));
        client.configureScenario(replay, new LiveFaults(1, null), true);
        client.beginTurn();
        assertThatThrownBy(() -> call(client)).isInstanceOf(BusinessException.class);
        assertThat(call(client).content()).isEqualTo("replayed");
        // the failed call is not recorded; the replayed one is
        assertThat(client.recorder().recordedTurns()).hasSize(1);
        assertThat(client.recorder().recordedTurns().get(0)).extracting(StreamTurnResult::content)
                .containsExactly("replayed");

        client.clearScenario();
        assertThat(client.recorder()).isNull();
        assertThat(call(client).content()).isEqualTo("live");
        assertThat(base.calls.get()).isEqualTo(2);
    }
}
