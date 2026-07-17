package com.wenjin.support;

import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.entity.CompanionMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-2：历史增量加载等价性——watermark 之前的消息已折叠进摘要，
 * 仅加载 id &gt; watermark 的消息时，BuiltContext 与全量路径一致。
 */
class ContextIncrementalLoadTest {

    private final LimitsProperties limits = new LimitsProperties();
    private final ConversationContextBuilder builder = new ConversationContextBuilder(limits);

    @Test
    @DisplayName("equivalentToFullLoad: 60 条历史 watermark=40，全量与增量 BuiltContext 逐字段相等")
    void equivalentToFullLoad() {
        final long watermark = 40L;
        final int totalMessages = 60;

        List<CompanionMessage> fullHistory = new ArrayList<>(totalMessages);
        for (int i = 1; i <= totalMessages; i++) {
            fullHistory.add(msg((long) i, i % 2 == 1 ? 1 : 2, "msg-" + i + "-content"));
        }

        // 已折叠进摘要的 1..watermark 内容（与 mergeSummary 风格一致，便于对照）
        String existingSummary = buildFoldedSummary(fullHistory.subList(0, (int) watermark));

        CompanionConversation conversation = new CompanionConversation();
        conversation.setId(1L);
        conversation.setSummaryText(existingSummary);
        conversation.setSummaryThroughMessageId(watermark);

        List<CompanionMessage> incrementalHistory = fullHistory.stream()
                .filter(m -> m.getId() != null && m.getId() > watermark)
                .collect(Collectors.toList());
        assertThat(incrementalHistory).hasSize(totalMessages - (int) watermark);
        assertThat(incrementalHistory.get(0).getId()).isEqualTo(watermark + 1);

        ConversationContextBuilder.BuiltContext fullCtx = builder.build(conversation, fullHistory);
        ConversationContextBuilder.BuiltContext incCtx = builder.build(conversation, incrementalHistory);

        assertThat(incCtx.summaryText()).isEqualTo(fullCtx.summaryText());
        assertThat(incCtx.summaryThroughMessageId()).isEqualTo(fullCtx.summaryThroughMessageId());
        assertThat(incCtx.summaryUpdated()).isEqualTo(fullCtx.summaryUpdated());
        assertThat(incCtx.recentMessages())
                .as("recent message sequence (id/role/content)")
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(fullCtx.recentMessages());
    }

    private static String buildFoldedSummary(List<CompanionMessage> folded) {
        StringBuilder sb = new StringBuilder();
        for (CompanionMessage m : folded) {
            String role = Integer.valueOf(1).equals(m.getRole()) ? "用户" : "助教";
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(role).append("：").append(m.getContent());
        }
        return sb.toString();
    }

    private static CompanionMessage msg(Long id, int role, String content) {
        CompanionMessage m = new CompanionMessage();
        m.setId(id);
        m.setConversationId(1L);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now().minusMinutes(100 - id));
        return m;
    }
}
