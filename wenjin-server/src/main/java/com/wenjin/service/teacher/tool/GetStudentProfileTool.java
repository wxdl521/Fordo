package com.wenjin.service.teacher.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.InsightCard;
import com.wenjin.service.companion.ToolContext;
import com.wenjin.service.companion.ToolException;
import com.wenjin.service.companion.ToolParamSpec;
import com.wenjin.service.teacher.TeacherAnalyticsModels.StudentNodeView;
import com.wenjin.service.teacher.TeacherAnalyticsModels.StudentProfile;
import com.wenjin.service.teacher.TeacherAnalyticsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单个学生画像（只读、可缓存）；同名多人返回候选，不自行猜测。 */
@Component
public class GetStudentProfileTool extends AbstractTeacherTool {

    public GetStudentProfileTool(TeacherAnalyticsService service, ObjectMapper objectMapper) {
        super(service, objectMapper);
    }

    @Override
    public String name() {
        return "get_student_profile";
    }

    @Override
    public String label() {
        return "正在查询该学生的学习画像…";
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(ToolParamSpec.string("student", true, 64, null));
    }

    @Override
    protected String description() {
        return "Learning profile of one enrolled student (name fuzzy match or student id): chapter mastery, weakest nodes, "
                + "last-14-day practice count/accuracy, recent mistake misconceptions (no stems), review backlog, plan progress. "
                + "If several students match, status=ambiguous with candidates (ask the teacher or retry with the id).";
    }

    @Override
    protected Map<String, Object> properties() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("student", prop("string", "Student name (fuzzy) or numeric student id"));
        return p;
    }

    @Override
    protected Output run(ToolContext ctx, JsonNode args) throws ToolException {
        String ref = strArg(args, "student");
        if (ref == null) {
            throw new ToolException("student is required");
        }
        StudentProfile profile = service.studentProfile(ctx.courseId(), ref);
        InsightCard card = null;
        if ("ok".equals(profile.status()) && profile.weakestNodes() != null && !profile.weakestNodes().isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            for (StudentNodeView n : profile.weakestNodes()) {
                rows.add(List.of(cell(n.name()), cell(n.chapter()), num(n.effective())));
            }
            card = new InsightCard(cell(profile.student().name()) + " 的最弱知识点",
                    List.of("知识点", "章节", "有效掌握"), rows, null);
        }
        return new Output(profile, card);
    }
}
