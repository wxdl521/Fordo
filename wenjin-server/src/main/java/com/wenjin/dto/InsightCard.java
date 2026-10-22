package com.wenjin.dto;

import java.util.List;

/**
 * 教师助教的表格卡片（SSE {@code insight_card}）：学生名单、题目统计等。
 * rows ≤ 50，单元格已由工具截断；只含学习数据，不含学生记忆或对话正文。
 */
public record InsightCard(String title, List<String> columns, List<List<String>> rows, String note) {
    public InsightCard {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : rows.stream().map(r -> r == null ? List.<String>of() : List.copyOf(r)).toList();
    }
}
