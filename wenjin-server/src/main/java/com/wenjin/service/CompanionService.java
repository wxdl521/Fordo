package com.wenjin.service;

import com.wenjin.dto.CodeResultVO;
import com.wenjin.dto.CompanionChatRequest;
import com.wenjin.dto.CompanionConversationVO;
import com.wenjin.dto.CompanionMessageVO;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.dto.ProbeStartVO;

import java.util.List;
import java.util.function.Consumer;

/**
 * AI 学习伴侣服务（PRD 9.2）：流式对话 + 会话持久化 + 画像系统提示。
 */
public interface CompanionService {

    abstract class CompanionStreamSink {

        public abstract void token(String token);

        public void toolStart(String name, String label) {
        }

        public void toolResult(String name, boolean ok) {
        }

        public void practiceCard(PracticeStartVO card) {
        }

        /** M9 understanding probe card (optional). */
        public void probeCard(ProbeStartVO card) {
        }

        /** run_code_check result card (direction 6); carries no source code. Sent right after tool_result. */
        public void codeResult(CodeResultVO card) {
        }

        /** 教师助教表格卡片（方向 7）；在对应 tool_result 之后发送。 */
        public void insightCard(com.wenjin.dto.InsightCard card) {
        }

        /**
         * 引用校验结论（RAG）：仅当最终回复含 {@code 资料 #N} 时在 done 之前发一次。
         * valid ⊆ 本 turn 可引用集合；invalid 为越界编号（持久化文本中已被替换）。
         */
        public void citations(java.util.List<Long> valid, java.util.List<Long> invalid) {
        }
    }

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

    void streamReply(Long conversationId, CompanionStreamSink sink);

    /**
     * 同 {@link #streamReply(Long, CompanionStreamSink)}，并为本轮携带已校验归属的复盘练习会话
     * （仅本 turn 有效，可空）。
     */
    default void streamReply(Long conversationId, Long reviewPracticeSessionId, CompanionStreamSink sink) {
        streamReply(conversationId, sink);
    }

    /**
     * 查询学生的会话列表（按更新时间倒序，keyset 分页）。
     *
     * @param studentId 学生 ID
     * @param courseId  课程 ID
     * @param beforeId  上一页最后一条会话 id（可空）
     * @param limit     条数（默认 30，最大 100）
     */
    List<CompanionConversationVO> listConversations(Long studentId, Long courseId, Long beforeId, Integer limit);

    default List<CompanionConversationVO> listConversations(Long studentId, Long courseId) {
        return listConversations(studentId, courseId, null, null);
    }

    /**
     * 查询会话消息（keyset 分页，返回时间正序）。
     *
     * @param conversationId 会话 ID
     * @param beforeId       上一页最早消息 id（可空=最新一页）
     * @param limit          条数（默认 30，最大 100）
     */
    List<CompanionMessageVO> getMessages(Long conversationId, Long beforeId, Integer limit);

    default List<CompanionMessageVO> getMessages(Long conversationId) {
        return getMessages(conversationId, null, null);
    }

    /**
     * 删除会话及其所有消息。
     *
     * @param conversationId 会话 ID
     */
    void deleteConversation(Long conversationId);
}
