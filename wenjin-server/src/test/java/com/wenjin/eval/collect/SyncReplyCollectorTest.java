package com.wenjin.eval.collect;

import com.wenjin.dto.PracticeStartVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncReplyCollectorTest {

    @Test
    @DisplayName("tokens 拼接为最终回复文本")
    void collectsTokensIntoFinalText() {
        SyncReplyCollector collector = new SyncReplyCollector();
        collector.token("你");
        collector.token("好");
        collector.token(null);
        collector.token("，");
        collector.token("世界");

        CollectedReply reply = collector.result();
        assertThat(reply.text()).isEqualTo("你好，世界");
        assertThat(reply.isBlank()).isFalse();
        assertThat(reply.practiceCards()).isEmpty();
    }

    @Test
    @DisplayName("toolStart/toolResult 事件可查询工具名")
    void collectsToolEvents() {
        SyncReplyCollector collector = new SyncReplyCollector();
        collector.toolStart("query_mastery", "正在查询…");
        collector.toolResult("query_mastery", true);
        collector.toolStart("schedule_review", "安排");
        collector.toolResult("schedule_review", false);
        collector.token("done");

        CollectedReply reply = collector.result();
        assertThat(reply.toolNamesCalled()).containsExactly("query_mastery", "schedule_review");
        assertThat(reply.toolEvents()).hasSize(2);
        assertThat(reply.toolEvents().get(0).ok()).isTrue();
        assertThat(reply.toolEvents().get(1).ok()).isFalse();
        assertThat(reply.toolEvents().get(0).label()).contains("查询");
    }

    @Test
    @DisplayName("practiceCard 列表累积且忽略 null")
    void collectsPracticeCards() {
        SyncReplyCollector collector = new SyncReplyCollector();
        PracticeStartVO card1 = new PracticeStartVO();
        card1.setSessionId(1L);
        PracticeStartVO card2 = new PracticeStartVO();
        card2.setSessionId(2L);

        collector.practiceCard(card1);
        collector.practiceCard(null);
        collector.practiceCard(card2);
        collector.token("练习已生成");

        CollectedReply reply = collector.result();
        assertThat(reply.practiceCards()).hasSize(2);
        assertThat(reply.practiceCards().get(0).getSessionId()).isEqualTo(1L);
        assertThat(reply.practiceCards().get(1).getSessionId()).isEqualTo(2L);
        assertThat(reply.text()).isEqualTo("练习已生成");
    }

    @Test
    @DisplayName("空收集器得到 blank reply")
    void emptyCollectorIsBlank() {
        SyncReplyCollector collector = new SyncReplyCollector();
        assertThat(collector.result().isBlank()).isTrue();
        assertThat(collector.text()).isEmpty();
    }
}
