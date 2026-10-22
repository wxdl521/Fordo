package com.wenjin.service.teacher.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.InsightCard;
import com.wenjin.service.companion.ToolContext;
import com.wenjin.service.companion.ToolException;
import com.wenjin.service.companion.ToolParamSpec;
import com.wenjin.service.teacher.TeacherAnalyticsModels.DistractorShare;
import com.wenjin.service.teacher.TeacherAnalyticsModels.QuestionErrorStats;
import com.wenjin.service.teacher.TeacherAnalyticsModels.QuestionStat;
import com.wenjin.service.teacher.TeacherAnalyticsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** 近 30 天错误率最高的客观题与干扰项分布（只读、可缓存；样本量不足 5 的题不排名）。 */
@Component
public class GetQuestionErrorStatsTool extends AbstractTeacherTool {

    public GetQuestionErrorStatsTool(TeacherAnalyticsService service, ObjectMapper objectMapper) {
        super(service, objectMapper);
    }

    @Override
    public String name() {
        return "get_question_error_stats";
    }

    @Override
    public String label() {
        return "正在统计题目错误率与干扰项…";
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.string("nodeCode", false, 128, null),
                ToolParamSpec.integer("limit", false, 1.0, (double) TeacherAnalyticsService.QUESTION_STATS_MAX));
    }

    @Override
    protected String description() {
        return "Objective questions (single/multiple choice, true-false) with the highest error rate in the last 30 days, "
                + "each with the share of students choosing each distractor and the misconception node it points to. "
                + "Questions with fewer than 5 attempts are not ranked. Optional nodeCode filter (primary node).";
    }

    @Override
    protected Map<String, Object> properties() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("nodeCode", prop("string", "Optional knowledge point code/name to restrict to"));
        Map<String, Object> limit = prop("integer",
                "Max questions (1-" + TeacherAnalyticsService.QUESTION_STATS_MAX + ", default 5)");
        limit.put("minimum", 1);
        limit.put("maximum", TeacherAnalyticsService.QUESTION_STATS_MAX);
        p.put("limit", limit);
        return p;
    }

    @Override
    protected Output run(ToolContext ctx, JsonNode args) throws ToolException {
        QuestionErrorStats stats = service.questionErrorStats(ctx.courseId(), strArg(args, "nodeCode"),
                intArg(args, "limit", 5));
        InsightCard card = null;
        if (!stats.questions().isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            for (QuestionStat q : stats.questions()) {
                String dist = q.distractors().stream()
                        .map((DistractorShare d) -> d.key() + " " + String.format(Locale.ROOT, "%.0f", d.sharePct())
                                + "%" + (d.misconceptionNode() == null ? "" : "→" + d.misconceptionNode().name()))
                        .collect(Collectors.joining("；"));
                rows.add(List.of(cell(q.stem()), q.node() == null ? "—" : cell(q.node().name()),
                        q.wrong() + "/" + q.attempts(),
                        String.format(Locale.ROOT, "%.0f%%", q.errorRatePct()),
                        cell(dist.isEmpty() ? "—" : dist)));
            }
            card = new InsightCard("错误率最高的题", List.of("题目", "知识点", "错/答", "错误率", "干扰项选择→误区"), rows,
                    "近 " + stats.windowDays() + " 天，样本>=" + stats.minSample());
        }
        return new Output(stats, card);
    }
}
