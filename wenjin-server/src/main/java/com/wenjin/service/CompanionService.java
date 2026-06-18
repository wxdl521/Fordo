package com.wenjin.service;

import com.wenjin.dto.CompanionChatRequest;
import com.wenjin.dto.CompanionConversationVO;
import com.wenjin.dto.CompanionMessageVO;

import java.util.List;
import java.util.function.Consumer;

/**
 * AI 学习伴侣服务（PRD 9.2）：流式对话 + 会话持久化 + 画像系统提示。
 */
public interface CompanionService {

    /**
     * 开始一轮对话：创建/复用会话，保存用户消息，返回会话 ID。
     *
     * @param req 对话请求（含 conversationId，首次为 null）
     * @return 会话 ID（新建或复用）
     */
    Long startTurn(CompanionChatRequest req);

    /**
     * 流式生成 AI 回复：构建系统提示+历史+当前问题，调用 AI 流式补全，逐 token 回调，累积完整回复后落库。
     *
     * @param conversationId 会话 ID（startTurn 返回）
     * @param onToken        每个增量 token 的回调
     */
    void streamReply(Long conversationId, Consumer<String> onToken);

    /**
     * 查询学生的所有会话（按更新时间倒序）。
     *
     * @param studentId 学生 ID
     * @param courseId  课程 ID
     * @return 会话列表
     */
    List<CompanionConversationVO> listConversations(Long studentId, Long courseId);

    /**
     * 查询会话内的所有消息（按创建时间正序）。
     *
     * @param conversationId 会话 ID
     * @return 消息列表
     */
    List<CompanionMessageVO> getMessages(Long conversationId);

    /**
     * 删除会话及其所有消息。
     *
     * @param conversationId 会话 ID
     */
    void deleteConversation(Long conversationId);
}
