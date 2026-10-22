package com.wenjin.service.teacher.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.InsightCard;
import com.wenjin.service.companion.ToolContext;
import com.wenjin.service.companion.ToolException;
import com.wenjin.service.companion.ToolParamSpec;
import com.wenjin.service.teacher.TeacherAnalyticsModels.NodeMasteryResult;
import com.wenjin.service.teacher.TeacherAnalyticsModels.NodeStudentView;
import com.wenjin.service.teacher.TeacherAnalyticsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单个知识点的全班掌握分布与未掌握学生名单（只读、可缓存）。 */
@Component
public class GetNodeMasteryTool extends AbstractTeacherTool {

    public GetNodeMasteryTool(TeacherAnalyticsService service, ObjectMapper objectMapper) {
        super(service, objectMapper);
    }

    @Override
    public String name() {
        return "get_node_mastery";
    }

    @Override
    public String label() {
        return "正在查询该知识点的全班掌握情况…";
    }

    @Override
    public List<ToolParamSpec> params() {
        return List.of(
                ToolParamSpec.string("nodeCode", false, 64, null),
                ToolParamSpec.string("nodeName", false, 128, null));
    }

    @Override
    protected String description() {
        return "Class mastery distribution of one knowledge point (nodeCode or nodeName; one is required) and the students who have not mastered it. "
                + "Ambiguous names return candidates.";
    }

    @Override
    protected Map<String, Object> properties() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("nodeCode", prop("string", "Knowledge point code, e.g. KT12"));
        p.put("nodeName", prop("string", "Knowledge point name (when the code is unknown)"));
        return p;
    }

    @Override
    protected Output run(ToolContext ctx, JsonNode args) throws ToolException {
        String ref = strArg(args, "nodeCode") != null ? strArg(args, "nodeCode") : strArg(args, "nodeName");
        if (ref == null) {
            throw new ToolException("nodeCode or nodeName is required");
        }
        NodeMasteryResult r = service.nodeMastery(ctx.courseId(), ref);
        InsightCard card = null;
        if ("ok".equals(r.status()) && r.notMastered() != null && !r.notMastered().isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            for (NodeStudentView s : r.notMastered()) {
                rows.add(List.of(cell(s.name()), num(s.effective()), s.level()));
            }
            card = new InsightCard("未掌握「" + cell(r.node().name()) + "」的学生",
                    List.of("学生", "有效掌握", "状态"), rows, r.note());
        }
        return new Output(r, card);
    }
}
