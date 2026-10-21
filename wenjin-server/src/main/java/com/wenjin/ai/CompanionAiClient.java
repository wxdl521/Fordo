package com.wenjin.ai;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI 学习伴侣流式对话客户端（OpenAI 兼容 /v1/chat/completions, stream:true）。
 * 与出题客户端分离：自由文本、不带 response_format=json_object。
 */
public interface CompanionAiClient {

    /** 一条对话消息。role 取 "user" / "assistant"。 */
    record ChatMsg(String role, String content) {}

    record ToolCall(String id, String name, String argumentsJson) {}

    sealed interface TurnOutcome permits FinalText, ToolCalls {}

    record FinalText(String text) implements TurnOutcome {}

    record ToolCalls(List<ToolCall> calls) implements TurnOutcome {}

    /**
     * 以系统提示 + 历史 + 当前用户消息发起流式补全，逐 token 回调 onToken。
     * 禁用/缺 key/网络异常抛 BusinessException(AI_ERROR)。
     *
     * @param systemPrompt 系统提示（白名单 + 画像，由 service 组好）
     * @param history      历史消息（不含本轮 user）
     * @param userMessage  本轮用户消息
     * @param onToken      每个增量 token 的回调
     */
    void stream(String systemPrompt, List<ChatMsg> history, String userMessage, Consumer<String> onToken);

    TurnOutcome completeWithTools(List<Map<String, Object>> messages, List<Map<String, Object>> toolSchemas);

    void streamMessages(List<Map<String, Object>> messages, Consumer<String> onToken);

    enum ToolChoice { AUTO, NONE }

    /** Token usage reported by the provider; cachedPromptTokens may be null. */
    record Usage(int promptTokens, int completionTokens, Integer cachedPromptTokens) {}

    /**
     * Result of one streamed LLM turn. usage is nullable. reasoningContent (nullable) is the
     * thinking-mode {@code reasoning_content} (e.g. DeepSeek): never shown or persisted, only
     * replayed on the assistant tool-call message within the same turn.
     */
    record StreamTurnResult(String content, List<ToolCall> toolCalls, Usage usage, String finishReason,
                            String reasoningContent) {
        public StreamTurnResult(String content, List<ToolCall> toolCalls, Usage usage, String finishReason) {
            this(content, toolCalls, usage, finishReason, null);
        }
    }

    /**
     * Streams one LLM turn with optional tools. Content deltas are forwarded immediately to
     * onContentDelta; tool_call deltas are accumulated. Exceptions thrown by onContentDelta
     * surface as {@link SinkFailureException} (never as AI_ERROR).
     */
    StreamTurnResult streamTurn(List<Map<String, Object>> messages,
                                List<Map<String, Object>> toolSchemas,
                                ToolChoice toolChoice,
                                Consumer<String> onContentDelta);

    /**
     * Raw phase-1 model output from the most recent {@link #completeWithTools} call, if available.
     * Used by M9 {@code Phase1DecisionParser} to read {@code intent}. Default: null.
     */
    default String lastPhase1Raw() {
        return null;
    }
}
