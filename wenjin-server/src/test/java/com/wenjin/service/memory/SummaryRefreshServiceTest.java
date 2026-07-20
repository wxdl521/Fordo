package com.wenjin.service.memory;

import com.wenjin.ai.SummaryRefreshAiClient;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.mapper.CompanionConversationMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummaryRefreshServiceTest {

    @Mock private CompanionConversationMapper conversationMapper;

    @Test
    @DisplayName("equality guard SQL：WHERE summary_through_message_id = expectedThrough")
    void equalityGuardSql() throws Exception {
        Method method = CompanionConversationMapper.class.getMethod(
                "updateSummaryRefreshGuarded", Long.class, String.class, Long.class);
        Update update = method.getAnnotation(Update.class);
        assertThat(update).isNotNull();
        String sql = String.join(" ", update.value()).toLowerCase();
        assertThat(sql).contains("summary_text");
        assertThat(sql).contains("summary_origin = 1");
        assertThat(sql).contains("summary_through_message_id =");
        assertThat(sql).doesNotContain("summary_through_message_id <");
        assertThat(sql).doesNotContain("node_code");
    }

    @Test
    @DisplayName("through 已变 → 跳过 LLM 写回")
    void throughMismatch_skips() {
        when(conversationMapper.selectById(1L)).thenReturn(conv(1L, "old", 200L));
        SummaryRefreshService service = new SummaryRefreshService(
                conversationMapper, (s, f) -> "new-summary");

        Map<String, Object> result = service.refresh(1L, 100L);

        assertThat(result.get("skipped")).isEqualTo("through_mismatch");
        assertThat(result.get("updated")).isEqualTo(false);
        verify(conversationMapper, never()).updateSummaryRefreshGuarded(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("through 匹配且 LLM 返回正文 → equality guard 写回")
    void matchingThrough_updates() {
        when(conversationMapper.selectById(1L)).thenReturn(conv(1L, "old", 80L));
        when(conversationMapper.updateSummaryRefreshGuarded(1L, "polished", 80L)).thenReturn(1);
        SummaryRefreshService service = new SummaryRefreshService(
                conversationMapper, (s, f) -> "polished");

        Map<String, Object> result = service.refresh(1L, 80L);

        assertThat(result.get("updated")).isEqualTo(true);
        verify(conversationMapper).updateSummaryRefreshGuarded(eq(1L), eq("polished"), eq(80L));
    }

    @Test
    @DisplayName("LLM 返回空 → 不写库")
    void emptyRefresh_skipsWrite() {
        when(conversationMapper.selectById(1L)).thenReturn(conv(1L, "old", 80L));
        SummaryRefreshService service = new SummaryRefreshService(
                conversationMapper, (SummaryRefreshAiClient) (s, f) -> "  ");

        Map<String, Object> result = service.refresh(1L, 80L);

        assertThat(result.get("skipped")).isEqualTo("empty_refresh");
        verify(conversationMapper, never()).updateSummaryRefreshGuarded(anyLong(), anyString(), anyLong());
    }

    private static CompanionConversation conv(long id, String summary, Long through) {
        CompanionConversation c = new CompanionConversation();
        c.setId(id);
        c.setSummaryText(summary);
        c.setSummaryThroughMessageId(through);
        return c;
    }
}
