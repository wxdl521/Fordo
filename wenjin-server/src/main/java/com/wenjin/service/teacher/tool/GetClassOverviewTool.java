package com.wenjin.service.teacher.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.InsightCard;
import com.wenjin.service.companion.ToolContext;
import com.wenjin.service.companion.ToolException;
import com.wenjin.service.teacher.TeacherAnalyticsModels.ChapterView;
import com.wenjin.service.teacher.TeacherAnalyticsModels.ClassOverview;
import com.wenjin.service.teacher.TeacherAnalyticsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 全班学情概览（只读、可缓存）：人数、平均掌握、分布、最弱知识点、章节汇总、近 7 天活跃。 */
@Component
public class GetClassOverviewTool extends AbstractTeacherTool {

    public GetClassOverviewTool(TeacherAnalyticsService service, ObjectMapper objectMapper) {
        super(service, objectMapper);
    }

    @Override
    public String name() {
        return "get_class_overview";
    }

    @Override
    public String label() {
        return "正在汇总全班学情…";
    }

    @Override
    protected String description() {
        return "Class-wide learning overview for the current course: enrolled students, average effective mastery (0-100), "
                + "student distribution (mastered/weak/notLearned/undiagnosed), 5 weakest knowledge points, per-chapter summary, "
                + "students active in the last 7 days. No parameters.";
    }

    @Override
    protected Map<String, Object> properties() {
        return new LinkedHashMap<>();
    }

    @Override
    protected Output run(ToolContext ctx, JsonNode args) throws ToolException {
        ClassOverview o = service.classOverview(ctx.courseId());
        InsightCard card = null;
        if (!o.chapters().isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            for (ChapterView c : o.chapters()) {
                if (rows.size() >= TeacherAnalyticsService.LIST_MAX_ROWS) {
                    break;
                }
                rows.add(List.of(cell(c.chapter()), String.valueOf(c.nodes()), num(c.avgEffective()),
                        String.valueOf(c.sampleSize())));
            }
            card = new InsightCard("章节掌握汇总", List.of("章节", "知识点数", "平均掌握", "样本数"), rows,
                    "掌握度为 0-100 的读时有效分");
        }
        return new Output(o, card);
    }
}
