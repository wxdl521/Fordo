package com.wenjin.eval.replay;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.golden.GoldenFile.GoldenRound;
import com.wenjin.eval.golden.GoldenFile.GoldenTurn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Golden text can cite DB-assigned chunk ids through {{cite:N}} placeholders. */
class ReplayCitePlaceholderTest {

    private static final List<Map<String, Object>> MESSAGES = List.of(
            Map.of("role", "system", "content", "资料 #5 在系统提示里不算"),
            Map.of("role", "tool", "tool_call_id", "a", "content",
                    "{\"items\":[{\"chunkId\":41,\"snippet\":\"x\",\"sourceTitle\":\"讲义A\"},{\"chunkId\":42}],\"count\":2}"),
            Map.of("role", "tool", "tool_call_id", "b", "content",
                    "{\"items\":[{\"chunkId\":42},{\"chunkId\":77,\"sourceTitle\":\"讲义B\"}],\"count\":2}"),
            Map.of("role", "tool", "tool_call_id", "c", "content", "not json"));

    @Test
    void resolvesNthDistinctChunkIdFromToolResults() {
        assertThat(ReplayCompanionAiClient.resolveCitePlaceholders(
                "A 资料 #{{cite:1}}，B 资料 #{{cite:2}}，C 资料 #{{cite:3}}", MESSAGES))
                .isEqualTo("A 资料 #41，B 资料 #42，C 资料 #77");
    }

    @Test
    void resolvesBySourceTitleIndependentOfRanking() {
        assertThat(ReplayCompanionAiClient.resolveCitePlaceholders(
                "B 资料 #{{cite:讲义B}}，A 资料 #{{cite:讲义A}}", MESSAGES))
                .isEqualTo("B 资料 #77，A 资料 #41");
        assertThat(ReplayCompanionAiClient.resolveCitePlaceholders("资料 #{{cite:不存在的讲义}}", MESSAGES))
                .isEqualTo("资料 #0");
    }

    @Test
    void unresolvablePlaceholderBecomesZeroSoItFailsValidation() {
        assertThat(ReplayCompanionAiClient.resolveCitePlaceholders("资料 #{{cite:9}}", MESSAGES))
                .isEqualTo("资料 #0");
        assertThat(ReplayCompanionAiClient.resolveCitePlaceholders("资料 #{{cite:1}}", List.of()))
                .isEqualTo("资料 #0");
    }

    @Test
    void textWithoutPlaceholderIsReturnedAsIs() {
        String plain = "没有占位符 资料 #12";
        assertThat(ReplayCompanionAiClient.resolveCitePlaceholders(plain, MESSAGES)).isSameAs(plain);
        assertThat(ReplayCompanionAiClient.resolveCitePlaceholders(null, MESSAGES)).isNull();
    }

    @Test
    void streamTurnDeliversResolvedText() {
        GoldenFile golden = new GoldenFile("S19", 2, "", "", true, List.of(new GoldenTurn(List.of(
                new GoldenRound("见资料 #{{cite:2}}", List.of(), null)))));
        ReplayCompanionAiClient client = new ReplayCompanionAiClient(golden);
        client.beginTurn();
        StringBuilder streamed = new StringBuilder();
        CompanionAiClient.StreamTurnResult r = client.streamTurn(MESSAGES, List.of(),
                CompanionAiClient.ToolChoice.AUTO, streamed::append);
        assertThat(r.content()).isEqualTo("见资料 #42");
        assertThat(streamed.toString()).isEqualTo("见资料 #42");
    }
}
