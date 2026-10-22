package com.wenjin.service.teacher.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.InsightCard;
import com.wenjin.service.companion.ToolContext;
import com.wenjin.service.companion.ToolException;
import com.wenjin.service.companion.ToolParamSpec;
import com.wenjin.service.teacher.TeacherAnalyticsModels.ActivityTrend;
import com.wenjin.service.teacher.TeacherAnalyticsModels.DayStat;
import com.wenjin.service.teacher.TeacherAnalyticsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 每日作答量 / 活跃学生 / 伴侣对话数趋势（只读、可缓存；对话只计数，不读内容）。 */
@Component
public class GetActivityTrendTool extends AbstractTeacherTool {

    public GetActivityTrendTool(TeacherAnalyticsService service, ObjectMapper objectMapper) {
        super(service, objectMapper);
    }

    @Override
    public String name() {
        return "get_activity_trend";
    }

    @Override
    public String label() {
        return "正在统计学习活跃趋势…";
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(ToolParamSpec.integer("days", false, 7.0, 30.0));
    }

    @Override
    protected String description() {
        return "Daily activity trend of the current course for the last N days (7-30, default 14): answers submitted, "
                + "active students, companion conversations (counts only).";
    }

    @Override
    protected Map<String, Object> properties() {
        Map<String, Object> p = new LinkedHashMap<>();
        Map<String, Object> days = prop("integer", "Window in days (7-30, default 14)");
        days.put("minimum", 7);
        days.put("maximum", 30);
        p.put("days", days);
        return p;
    }

    @Override
    protected Output run(ToolContext ctx, JsonNode args) throws ToolException {
        ActivityTrend t = service.activityTrend(ctx.courseId(), intArg(args, "days", 14));
        List<DayStat> daily = t.daily();
        int max = TeacherAnalyticsService.LIST_MAX_ROWS;
        List<DayStat> shown = daily.size() > max ? daily.subList(daily.size() - max, daily.size()) : daily;
        List<List<String>> rows = new ArrayList<>();
        for (DayStat d : shown) {
            rows.add(List.of(d.date(), String.valueOf(d.answers()), String.valueOf(d.activeStudents()),
                    String.valueOf(d.companionConversations())));
        }
        InsightCard card = new InsightCard("每日学习活跃", List.of("日期", "作答数", "活跃学生", "伴侣对话"), rows,
                shown.size() < daily.size() ? "仅显示最近 " + shown.size() + " 天（完整数据见工具结果）" : null);
        return new Output(t, card);
    }
}
