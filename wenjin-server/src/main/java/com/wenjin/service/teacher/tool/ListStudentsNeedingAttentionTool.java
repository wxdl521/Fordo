package com.wenjin.service.teacher.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.InsightCard;
import com.wenjin.service.companion.ToolContext;
import com.wenjin.service.companion.ToolException;
import com.wenjin.service.companion.ToolParamSpec;
import com.wenjin.service.teacher.TeacherAnalyticsModels.AttentionItem;
import com.wenjin.service.teacher.TeacherAnalyticsModels.AttentionList;
import com.wenjin.service.teacher.TeacherAnalyticsModels.Reason;
import com.wenjin.service.teacher.TeacherAnalyticsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 需要关注的学生名单（只读、可缓存），按可解释的综合关注分排序。 */
@Component
public class ListStudentsNeedingAttentionTool extends AbstractTeacherTool {

    public ListStudentsNeedingAttentionTool(TeacherAnalyticsService service, ObjectMapper objectMapper) {
        super(service, objectMapper);
    }

    @Override
    public String name() {
        return "list_students_needing_attention";
    }

    @Override
    public String label() {
        return "正在筛选需要关注的学生…";
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.integer("limit", false, 1.0, (double) TeacherAnalyticsService.LIST_MAX_ROWS),
                new ToolParamSpec("reason", ToolParamSpec.ParamType.STRING, false, 32, null, null, Reason.ALL, null));
    }

    @Override
    protected String description() {
        return "Rank students of the current course who need attention, with explainable reasons "
                + "(low mastery, undiagnosed, 7-day drop, no recent practice, many recent wrong answers, review backlog). "
                + "Optional reason filter.";
    }

    @Override
    protected Map<String, Object> properties() {
        Map<String, Object> p = new LinkedHashMap<>();
        Map<String, Object> limit = prop("integer",
                "Max students to return (1-" + TeacherAnalyticsService.LIST_MAX_ROWS + ", default 10)");
        limit.put("minimum", 1);
        limit.put("maximum", TeacherAnalyticsService.LIST_MAX_ROWS);
        p.put("limit", limit);
        Map<String, Object> reason = prop("string", "Only students with this reason code");
        reason.put("enum", Reason.ALL);
        p.put("reason", reason);
        return p;
    }

    @Override
    protected Output run(ToolContext ctx, JsonNode args) throws ToolException {
        AttentionList list;
        try {
            list = service.studentsNeedingAttention(ctx.courseId(), intArg(args, "limit", 10), strArg(args, "reason"));
        } catch (IllegalArgumentException e) {
            throw new ToolException("reason must be one of " + Reason.ALL);
        }
        InsightCard card = null;
        if (!list.students().isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            for (AttentionItem s : list.students()) {
                rows.add(List.of(cell(s.name()), num(s.avgEffective()), cell(String.join("；", s.reasons())),
                        cell(s.lastPracticeAt() == null ? "从未" : s.lastPracticeAt())));
            }
            card = new InsightCard("需要关注的学生", List.of("学生", "平均掌握", "主要原因", "最近练习"), rows, list.note());
        }
        return new Output(list, card);
    }
}
