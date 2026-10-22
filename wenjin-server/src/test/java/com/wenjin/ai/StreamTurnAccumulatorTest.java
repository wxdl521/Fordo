package com.wenjin.ai;

import com.wenjin.ai.CompanionAiClient.StreamTurnResult;
import com.wenjin.ai.CompanionAiClient.ToolChoice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamTurnAccumulatorTest {

    private static void feed(StreamTurnAccumulator acc, String... lines) {
        for (String l : lines) {
            acc.accept(l);
        }
    }

    @Test
    void pureContentStreamForwardsDeltasAndReadsUsage() {
        List<String> deltas = new ArrayList<>();
        StreamTurnAccumulator acc = new StreamTurnAccumulator(deltas::add);
        feed(acc,
                "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"thinking...\"}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"，同学\"}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":8,\"total_tokens\":128,"
                        + "\"prompt_cache_hit_tokens\":100,\"prompt_cache_miss_tokens\":20}}",
                "data: [DONE]");
        StreamTurnResult r = acc.result();
        assertThat(deltas).containsExactly("你好", "，同学");
        assertThat(r.content()).isEqualTo("你好，同学");
        assertThat(r.toolCalls()).isEmpty();
        assertThat(r.finishReason()).isEqualTo("stop");
        assertThat(r.usage()).isEqualTo(new CompanionAiClient.Usage(120, 8, 100));
    }

    @Test
    void openAiCachedTokensDetailsAndMissingUsage() {
        StreamTurnAccumulator acc = new StreamTurnAccumulator(d -> { });
        feed(acc,
                "data: {\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":2,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":30}}}");
        assertThat(acc.result().usage()).isEqualTo(new CompanionAiClient.Usage(50, 2, 30));

        StreamTurnAccumulator noUsage = new StreamTurnAccumulator(d -> { });
        feed(noUsage, "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}", "data: [DONE]");
        assertThat(noUsage.result().usage()).isNull();
        assertThat(noUsage.result().finishReason()).isNull();

        StreamTurnAccumulator noCache = new StreamTurnAccumulator(d -> { });
        feed(noCache, "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}");
        assertThat(noCache.result().usage()).isEqualTo(new CompanionAiClient.Usage(5, 1, null));
    }

    @Test
    void parallelToolCallsSplitAcrossChunksWithInterleavedIndices() {
        List<String> deltas = new ArrayList<>();
        StreamTurnAccumulator acc = new StreamTurnAccumulator(deltas::add);
        feed(acc,
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"我来查一下\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"query_mastery\",\"arguments\":\"\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call_b\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_node_detail\",\"arguments\":\"\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"nodeC\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"{\\\"nodeCode\\\":\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ode\\\":\\\"SE-1.1\\\"}\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"\\\"SE-1.2\\\"}\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":30}}",
                "data: [DONE]");
        StreamTurnResult r = acc.result();
        assertThat(deltas).containsExactly("我来查一下");
        assertThat(r.finishReason()).isEqualTo("tool_calls");
        assertThat(r.toolCalls()).containsExactly(
                new CompanionAiClient.ToolCall("call_a", "query_mastery", "{\"nodeCode\":\"SE-1.1\"}"),
                new CompanionAiClient.ToolCall("call_b", "get_node_detail", "{\"nodeCode\":\"SE-1.2\"}"));
    }

    @Test
    void toolCallWithoutArgumentsDefaultsToEmptyObjectAndNamelessIsDropped() {
        StreamTurnAccumulator acc = new StreamTurnAccumulator(d -> { });
        feed(acc,
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
                        + "\"function\":{\"name\":\"get_review_queue\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"c2\","
                        + "\"function\":{\"arguments\":\"{}\"}}]}}]}");
        assertThat(acc.result().toolCalls()).containsExactly(
                new CompanionAiClient.ToolCall("c1", "get_review_queue", "{}"));
    }

    @Test
    void ignoresMalformedAndNonDataLines() {
        List<String> deltas = new ArrayList<>();
        StreamTurnAccumulator acc = new StreamTurnAccumulator(deltas::add);
        feed(acc, ": keep-alive", "event: ping", "data: {bad", "data: ",
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}");
        assertThat(deltas).containsExactly("ok");
    }

    @Test
    void callbackFailureIsWrappedAsSinkFailureException() {
        StreamTurnAccumulator acc = new StreamTurnAccumulator(d -> {
            throw new IllegalStateException("client gone");
        });
        assertThatThrownBy(() -> acc.accept("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"))
                .isInstanceOf(SinkFailureException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void callbackSinkFailureExceptionPropagatesUnwrapped() {
        SinkFailureException original = new SinkFailureException("gone", new RuntimeException("io"));
        StreamTurnAccumulator acc = new StreamTurnAccumulator(d -> {
            throw original;
        });
        assertThatThrownBy(() -> acc.accept("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"))
                .isSameAs(original);
    }

    @Test
    void requestBodyWithToolsAndChoice() {
        Map<String, Object> schema = Map.of("type", "function", "function", Map.of("name", "lookup"));
        Map<String, Object> auto = OpenAiCompatibleCompanionAiClient.buildStreamTurnRequestBody(
                "m", 0.3, List.of(Map.of("role", "user", "content", "hi")), List.of(schema), ToolChoice.AUTO);
        assertThat(auto).containsEntry("stream", true)
                .containsEntry("tool_choice", "auto")
                .containsEntry("model", "m")
                .containsEntry("temperature", 0.3);
        assertThat(auto.get("tools")).isEqualTo(List.of(schema));
        assertThat(auto.get("stream_options")).isEqualTo(Map.of("include_usage", true));

        Map<String, Object> none = OpenAiCompatibleCompanionAiClient.buildStreamTurnRequestBody(
                "m", 0.3, null, List.of(schema), ToolChoice.NONE);
        assertThat(none).containsEntry("tool_choice", "none").containsEntry("messages", List.of());
        assertThat(none.get("tools")).isEqualTo(List.of(schema));
    }

    @Test
    void requestBodyWithoutSchemasOmitsToolFields() {
        Map<String, Object> body = OpenAiCompatibleCompanionAiClient.buildStreamTurnRequestBody(
                "m", 0.3, List.of(), List.of(), ToolChoice.AUTO);
        assertThat(body).doesNotContainKeys("tools", "tool_choice");
        assertThat(body).containsEntry("stream", true);
        assertThat(body.get("stream_options")).isEqualTo(Map.of("include_usage", true));
        assertThat(OpenAiCompatibleCompanionAiClient.buildStreamTurnRequestBody("m", 0.3, null, null, null))
                .doesNotContainKeys("tools", "tool_choice");
    }

    @Test
    void reasoningContentIsAccumulatedButNeverForwarded() {
        List<String> deltas = new ArrayList<>();
        StreamTurnAccumulator acc = new StreamTurnAccumulator(deltas::add);
        feed(acc,
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先想\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"一想\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
                        + "\"function\":{\"name\":\"query_mastery\",\"arguments\":\"{}\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        StreamTurnResult r = acc.result();
        assertThat(deltas).isEmpty();
        assertThat(r.reasoningContent()).isEqualTo("先想一想");
        assertThat(r.content()).isEmpty();
    }

    @Test
    void noReasoningContentYieldsNull() {
        StreamTurnAccumulator acc = new StreamTurnAccumulator(d -> { });
        feed(acc, "data: {\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}");
        assertThat(acc.result().reasoningContent()).isNull();
    }

    @Test
    void errorFrameRaisesAiError() {
        List<String> deltas = new ArrayList<>();
        StreamTurnAccumulator acc = new StreamTurnAccumulator(deltas::add);
        acc.accept("data: {\"choices\":[{\"delta\":{\"content\":\"部分\"}}]}");
        assertThatThrownBy(() -> acc.accept(
                "data: {\"error\":{\"message\":\"overloaded\",\"type\":\"server_error\"}}"))
                .isInstanceOf(com.wenjin.common.BusinessException.class)
                .isNotInstanceOf(SinkFailureException.class)
                .hasMessageContaining("overloaded")
                .satisfies(e -> assertThat(((com.wenjin.common.BusinessException) e).getCode())
                        .isEqualTo(com.wenjin.common.ResultCode.AI_ERROR.getCode()));
    }

    @Test
    void emptyStreamWithoutFinishReasonIsLlmFailure() {
        StreamTurnAccumulator acc = new StreamTurnAccumulator(d -> { });
        feed(acc, ": keep-alive", "data: [DONE]");
        assertThatThrownBy(acc::result)
                .isInstanceOf(com.wenjin.common.BusinessException.class)
                .isNotInstanceOf(SinkFailureException.class);
    }

    @Test
    void emptyContentWithFinishReasonIsNotAFailure() {
        StreamTurnAccumulator acc = new StreamTurnAccumulator(d -> { });
        feed(acc, "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        assertThat(acc.result().content()).isEmpty();
    }
}
