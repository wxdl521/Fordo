package com.wenjin.service.memory;

import com.wenjin.ai.SummaryRefreshAiClient;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.mapper.CompanionConversationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SUMMARY_REFRESH 作业执行体：LLM 润色摘要，并以 equality guard 写回。
 * <p>请求路径仍只走 {@link com.wenjin.mapper.CompanionConversationMapper#updateSummaryGuarded}
 * 的确定性 mergeSummary；本服务不得被 HTTP 同步路径调用。
 */
@Service
public class SummaryRefreshService {

    private static final Logger log = LoggerFactory.getLogger(SummaryRefreshService.class);

    private final CompanionConversationMapper conversationMapper;
    private final SummaryRefreshAiClient refreshAiClient;

    public SummaryRefreshService(CompanionConversationMapper conversationMapper,
                                 SummaryRefreshAiClient refreshAiClient) {
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.refreshAiClient = Objects.requireNonNull(refreshAiClient);
    }

    /**
     * @param conversationId 会话
     * @param expectedThrough 派发时的 summary_through_message_id；必须仍相等才写回
     */
    @Transactional
    public Map<String, Object> refresh(Long conversationId, Long expectedThrough) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversationId);
        result.put("expectedThrough", expectedThrough);
        result.put("updated", false);

        if (conversationId == null || expectedThrough == null) {
            result.put("skipped", "missing_ids");
            return result;
        }

        CompanionConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            result.put("skipped", "conversation_not_found");
            return result;
        }
        if (!expectedThrough.equals(conv.getSummaryThroughMessageId())) {
            result.put("skipped", "through_mismatch");
            return result;
        }

        String refreshed = refreshAiClient.refresh(conv.getSummaryText(), null);
        if (!StringUtils.hasText(refreshed)) {
            result.put("skipped", "empty_refresh");
            return result;
        }

        int rows = conversationMapper.updateSummaryRefreshGuarded(
                conversationId, refreshed.trim(), expectedThrough);
        result.put("updated", rows > 0);
        if (rows == 0) {
            log.debug("summary refresh lost race: conversationId={} expectedThrough={}",
                    conversationId, expectedThrough);
            result.put("skipped", "equality_guard");
        }
        return result;
    }

    public static Map<String, Object> requestBody(long conversationId, long expectedThrough,
                                                 long studentId, long courseId) {
        Map<String, Object> body = new HashMap<>();
        body.put("conversationId", conversationId);
        body.put("expectedThrough", expectedThrough);
        body.put("studentId", studentId);
        body.put("courseId", courseId);
        return body;
    }

    public static String requestKey(long conversationId, long throughId) {
        return "conv:" + conversationId + ":summary:" + throughId;
    }
}
