package com.wenjin.ai;

import java.util.List;
import java.util.function.Consumer;

/**
 * AI 学习伴侣流式对话客户端（OpenAI 兼容 /v1/chat/completions, stream:true）。
 * 与出题客户端分离：自由文本、不带 response_format=json_object。
 */
public interface CompanionAiClient {

    /** 一条对话消息。role 取 "user" / "assistant"。 */
    record ChatMsg(String role, String content) {}

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
}
