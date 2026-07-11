package com.wenjin.support;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.entity.CompanionMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationContextBuilderTest {

    private final LimitsProperties limits = new LimitsProperties();
    private final ConversationContextBuilder builder = new ConversationContextBuilder(limits);

    @Test
    void hundredMessages_yieldsSummaryPlusLatestTwenty_withinCharBudget() {
        CompanionConversation conv = new CompanionConversation();
        conv.setId(1L);

        List<CompanionMessage> history = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            CompanionMessage m = new CompanionMessage();
            m.setId((long) i);
            m.setRole(i % 2 == 1 ? 1 : 2);
            m.setContent("msg-" + i + "-content");
            m.setCreatedAt(LocalDateTime.now().minusMinutes(100 - i));
            history.add(m);
        }

        ConversationContextBuilder.BuiltContext ctx = builder.build(conv, history);

        assertThat(ctx.summaryUpdated()).isTrue();
        assertThat(ctx.summaryText()).isNotBlank();
        assertThat(ctx.recentMessages()).hasSize(20);
        assertThat(ctx.recentMessages().get(ctx.recentMessages().size() - 1).getContent())
                .isEqualTo("msg-100-content");
        assertThat(ctx.summaryThroughMessageId()).isEqualTo(80L);

        ConversationContextBuilder.LegacyPrompt legacy = builder.toLegacyPrompt(ctx);
        assertThat(legacy.latestUserMessage()).isEqualTo("msg-100-content");
        int totalChars = (ctx.summaryText() == null ? 0 : ctx.summaryText().length())
                + ctx.recentMessages().stream().mapToInt(m -> m.getContent().length()).sum();
        assertThat(totalChars).isLessThanOrEqualTo(limits.getCompanion().getMaxContextChars());

        // tool messages 也应含摘要伪对话 + 近窗
        List<?> toolMsgs = builder.toToolMessages("sys", ctx);
        assertThat(toolMsgs.size()).isGreaterThan(20);
    }

    @Test
    void shortHistory_noSummaryUpdate() {
        CompanionConversation conv = new CompanionConversation();
        List<CompanionMessage> history = List.of(msg(1L, 1, "hi"), msg(2L, 2, "hello"));
        ConversationContextBuilder.BuiltContext ctx = builder.build(conv, history);
        assertThat(ctx.summaryUpdated()).isFalse();
        assertThat(ctx.recentMessages()).hasSize(2);
    }

    @Test
    void keepLatestUserWhenBudgetTight() {
        limits.getCompanion().setMaxContextChars(30);
        limits.getCompanion().setMaxRecentMessages(5);
        CompanionConversation conv = new CompanionConversation();
        List<CompanionMessage> history = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            history.add(msg((long) i, i == 5 ? 1 : 2, "xxxxxxxxxxxxxxxxxxxx-" + i));
        }
        ConversationContextBuilder.BuiltContext ctx = builder.build(conv, history);
        assertThat(ctx.recentMessages()).isNotEmpty();
        assertThat(ctx.recentMessages().get(ctx.recentMessages().size() - 1).getContent())
                .startsWith("xxxxxxxxxxxxxxxxxxxx-5");
    }

    @Test
    void appendOutputToken_truncatesAtCap() {
        StringBuilder sb = new StringBuilder();
        boolean over1 = builder.appendOutputToken(sb, "abcd", 3);
        assertThat(over1).isTrue();
        assertThat(sb).hasToString("abc");
        boolean over2 = builder.appendOutputToken(sb, "x", 3);
        assertThat(over2).isTrue();
        assertThat(sb).hasToString("abc");
    }

    @Test
    void clampListLimit_defaultAndMax() {
        assertThat(builder.clampListLimit(null)).isEqualTo(30);
        assertThat(builder.clampListLimit(0)).isEqualTo(30);
        assertThat(builder.clampListLimit(50)).isEqualTo(50);
        assertThat(builder.clampListLimit(1000)).isEqualTo(100);
    }

    @Test
    void legacyPrompt_includesSummaryAsPseudoTurns() {
        CompanionConversation conv = new CompanionConversation();
        conv.setSummaryText("old stuff");
        conv.setSummaryThroughMessageId(1L);
        List<CompanionMessage> history = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            history.add(msg((long) i, i % 2 == 1 ? 1 : 2, "m" + i));
        }
        ConversationContextBuilder.BuiltContext ctx = builder.build(conv, history);
        ConversationContextBuilder.LegacyPrompt p = builder.toLegacyPrompt(ctx);
        assertThat(p.history().stream().map(CompanionAiClient.ChatMsg::content).anyMatch(c -> c.contains("历史摘要") || c.contains("old")))
                .isTrue();
    }

    private static CompanionMessage msg(Long id, int role, String content) {
        CompanionMessage m = new CompanionMessage();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }
}
