package com.wenjin.eval.fixture;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** LIVE S22：错题夹具的干扰项须体现误区节点内容，模型才能对比所选与正确选项讲清错因。 */
class JdbcEvalMistakePortTest {

    @Test
    void distractorAttributesMisconceptionContentToQuestionNode() {
        assertThat(JdbcEvalMistakePort.distractorText("用例图", "需求获取", "访谈、问卷、观察等需求获取方法"))
                .isEqualTo("「用例图」的核心内容就是访谈、问卷、观察等需求获取方法");
        assertThat(JdbcEvalMistakePort.distractorText("数据流图", "需求规格说明", null))
                .isEqualTo("「数据流图」的核心内容就是需求规格说明");
        assertThat(JdbcEvalMistakePort.distractorText(null, "需求获取", "x")).isNull();
        assertThat(JdbcEvalMistakePort.distractorText("用例图", null, " ")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordMistakeTagsOptionWithMisconceptionNodeAndText() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM question q"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(7L));
        when(jdbc.queryForList(contains("SELECT option_key"), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("B"));
        when(jdbc.queryForList(contains("SELECT name FROM kg_node"), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("用例图"));
        when(jdbc.queryForList(contains("SELECT name, description"), any(Object[].class)))
                .thenReturn(List.of(Map.of("name", "需求获取", "description", "访谈、问卷、观察等需求获取方法")));

        boolean ok = new JdbcEvalMistakePort(jdbc).recordMistake(1L, 2L, 3L, "SE-2.1", 5);

        assertThat(ok).isTrue();
        verify(jdbc).update(contains("SET point_node_code = ?, option_text = ?"),
                eq("SE-2.1"), eq("「用例图」的核心内容就是访谈、问卷、观察等需求获取方法"), eq(7L), eq("B"));
        verify(jdbc).update(contains("INSERT INTO answer_record"), any(), any(), any(), anyString(), any());
    }
}
