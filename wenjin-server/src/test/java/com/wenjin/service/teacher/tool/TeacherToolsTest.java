package com.wenjin.service.teacher.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.service.companion.CompanionTool;
import com.wenjin.service.companion.ToolAudience;
import com.wenjin.service.companion.ToolContext;
import com.wenjin.service.companion.ToolException;
import com.wenjin.service.companion.ToolExecutionResult;
import com.wenjin.service.companion.ToolParamSpec;
import com.wenjin.service.teacher.TeacherAnalyticsModels.*;
import com.wenjin.service.teacher.TeacherAnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TeacherToolsTest {

    private final ObjectMapper om = new ObjectMapper();
    private final TeacherAnalyticsService service = mock(TeacherAnalyticsService.class);
    private final ToolContext teacher = ToolContext.forTeacher(7L, 10L);
    private List<AbstractTeacherTool> tools;

    @BeforeEach
    void setUp() {
        tools = List.of(new GetClassOverviewTool(service, om), new ListStudentsNeedingAttentionTool(service, om),
                new GetStudentProfileTool(service, om), new GetNodeMasteryTool(service, om),
                new GetQuestionErrorStatsTool(service, om), new GetActivityTrendTool(service, om));
    }

    private JsonNode args(String json) throws Exception {
        return om.readTree(json);
    }

    @Test
    void allToolsAreTeacherReadOnlyWithoutCourseIdParam() {
        assertThat(tools).extracting(CompanionTool::name).containsExactly(
                "get_class_overview", "list_students_needing_attention", "get_student_profile",
                "get_node_mastery", "get_question_error_stats", "get_activity_trend");
        for (AbstractTeacherTool t : tools) {
            assertThat(t.audience()).isEqualTo(ToolAudience.TEACHER);
            assertThat(t.sideEffecting()).isFalse();
            assertThat(t.label()).matches(".*[\\u4e00-\\u9fa5].*");
            assertThat(t.params()).extracting(ToolParamSpec::name).doesNotContain("courseId");
            String schema = t.schema().toString();
            assertThat(schema).doesNotContain("courseId").contains("additionalProperties=false");
            assertThat(t.available()).isTrue();
        }
    }

    @Test
    void studentOrContextlessCallsAreRejectedWithoutTouchingTheService() throws Exception {
        ToolContext student = new ToolContext(5L, 10L);
        for (AbstractTeacherTool t : tools) {
            ToolExecutionResult r = t.executeForGovernor("c1", student, args("{\"student\":\"a\",\"nodeCode\":\"K\"}"));
            assertThat(r.ok()).isFalse();
            assertThat(r.guarded()).isTrue();
            assertThat(om.readTree(r.content()).path("code").asText()).isEqualTo("TOOL_NOT_ALLOWED");
            assertThatThrownBy(() -> t.execute(student, args("{}"))).isInstanceOf(ToolException.class);
        }
        verifyNoInteractions(service);
    }

    @Test
    void classOverviewReturnsCompactJsonAndChapterCard() throws Exception {
        when(service.classOverview(10L)).thenReturn(new ClassOverview(4, 3, 55.0, new StudentDistribution(1, 1, 1, 1),
                List.of(new WeakNodeView("KT3", "类", "第二章", 45.0, 2)),
                List.of(new ChapterView("第一章", 2, 60.0, 5)), 2, null));

        ToolExecutionResult r = tools.get(0).executeForGovernor("c1", teacher, args("{}"));

        JsonNode j = om.readTree(r.content());
        assertThat(j.path("students").asInt()).isEqualTo(4);
        assertThat(j.at("/distribution/undiagnosed").asInt()).isEqualTo(1);
        assertThat(j.at("/weakestNodes/0/nodeCode").asText()).isEqualTo("KT3");
        assertThat(j.has("note")).isFalse();
        assertThat(r.insightCard().columns()).containsExactly("章节", "知识点数", "平均掌握", "样本数");
        assertThat(r.insightCard().rows()).containsExactly(List.of("第一章", "2", "60.0", "5"));
    }

    @Test
    void emptyCourseOverviewHasNoCard() throws Exception {
        when(service.classOverview(10L)).thenReturn(new ClassOverview(0, 0, null, new StudentDistribution(0, 0, 0, 0),
                List.of(), List.of(), 0, "该课程暂无选课学生"));
        ToolExecutionResult r = tools.get(0).executeForGovernor("c1", teacher, args("{}"));
        assertThat(r.insightCard()).isNull();
        assertThat(om.readTree(r.content()).path("note").asText()).contains("暂无");
    }

    @Test
    void attentionToolPassesParamsAndBuildsTruncatedCard() throws Exception {
        String longReason = "x".repeat(200);
        when(service.studentsNeedingAttention(10L, 5, "DROP")).thenReturn(new AttentionList(3, 1, List.of(
                new AttentionItem(2L, "李四", 20.0, 58, List.of("DROP"), List.of(longReason), null, null)), null));

        ToolExecutionResult r = tools.get(1).executeForGovernor("c1", teacher, args("{\"limit\":5,\"reason\":\"DROP\"}"));

        assertThat(r.insightCard().columns()).containsExactly("学生", "平均掌握", "主要原因", "最近练习");
        List<String> row = r.insightCard().rows().get(0);
        assertThat(row.get(0)).isEqualTo("李四");
        assertThat(row.get(2).length()).isLessThanOrEqualTo(AbstractTeacherTool.CELL_MAX + 1);
        assertThat(row.get(3)).isEqualTo("从未");
        assertThat(om.readTree(r.content()).at("/students/0/reasonCodes/0").asText()).isEqualTo("DROP");
    }

    @Test
    void attentionToolDefaultsLimitAndDeclaresReasonEnum() throws Exception {
        when(service.studentsNeedingAttention(10L, 10, null)).thenReturn(new AttentionList(0, 0, List.of(), "暂无"));
        ToolExecutionResult r = tools.get(1).executeForGovernor("c1", teacher, args("{}"));
        assertThat(r.insightCard()).isNull();
        verify(service).studentsNeedingAttention(10L, 10, null);
        ToolParamSpec reason = tools.get(1).params().stream().filter(p -> p.name().equals("reason")).findFirst().orElseThrow();
        assertThat(reason.enumValues()).contains("LOW_MASTERY", "DROP", "NO_PRACTICE", "REVIEW_BACKLOG");
    }

    @Test
    void studentProfileRequiresStudentAndReturnsAmbiguityWithoutCard() throws Exception {
        assertThatThrownBy(() -> tools.get(2).executeForGovernor("c1", teacher, args("{}")))
                .isInstanceOf(ToolException.class);
        when(service.studentProfile(10L, "张")).thenReturn(StudentProfile.ambiguous(
                List.of(new StudentRef(1, "张三"), new StudentRef(2, "张丰")), "有多名学生匹配"));
        ToolExecutionResult r = tools.get(2).executeForGovernor("c1", teacher, args("{\"student\":\"张\"}"));
        JsonNode j = om.readTree(r.content());
        assertThat(j.path("status").asText()).isEqualTo("ambiguous");
        assertThat(j.path("candidates")).hasSize(2);
        assertThat(j.has("chapters")).isFalse();
        assertThat(r.insightCard()).isNull();
    }

    @Test
    void studentProfileOkHasWeakestNodesCard() throws Exception {
        when(service.studentProfile(10L, "李四")).thenReturn(new StudentProfile("ok", new StudentRef(2, "李四"), 20.0, 3, 3,
                List.of(), List.of(new StudentNodeView("KT3", "类", "第二章", 10.0)),
                new PracticeWindow(14, 10, 4, 40.0, null), List.of(), List.of(), 1, null, null, null));
        ToolExecutionResult r = tools.get(2).executeForGovernor("c1", teacher, args("{\"student\":\"李四\"}"));
        assertThat(r.insightCard().title()).contains("李四");
        assertThat(r.insightCard().rows()).containsExactly(List.of("类", "第二章", "10.0"));
        assertThat(om.readTree(r.content()).at("/practice14d/accuracyPct").asDouble()).isEqualTo(40.0);
    }

    @Test
    void nodeMasteryAcceptsCodeOrNameAndBuildsStudentCard() throws Exception {
        when(service.nodeMastery(10L, "KT1")).thenReturn(new NodeMasteryResult("ok", new NodeRef("KT1", "变量"), "第一章", 4,
                new StudentDistribution(1, 1, 1, 1), 56.7, 3,
                List.of(new NodeStudentView(2, "李四", 20.0, "未学"), new NodeStudentView(4, "赵六", null, "未诊断")),
                null, null));
        when(service.nodeMastery(10L, "变量")).thenReturn(NodeMasteryResult.notFound("没有匹配"));

        ToolExecutionResult r = tools.get(3).executeForGovernor("c1", teacher, args("{\"nodeCode\":\"KT1\"}"));
        assertThat(r.insightCard().columns()).containsExactly("学生", "有效掌握", "状态");
        assertThat(r.insightCard().rows().get(1)).containsExactly("赵六", "—", "未诊断");

        ToolExecutionResult byName = tools.get(3).executeForGovernor("c1", teacher, args("{\"nodeName\":\"变量\"}"));
        assertThat(om.readTree(byName.content()).path("status").asText()).isEqualTo("not_found");
        assertThat(byName.insightCard()).isNull();
        assertThatThrownBy(() -> tools.get(3).executeForGovernor("c1", teacher, args("{}"))).isInstanceOf(ToolException.class);
    }

    @Test
    void questionStatsCardShowsDistractorsWithMisconceptions() throws Exception {
        QuestionStat q = new QuestionStat(1, "题干", 1, new NodeRef("KT2", "循环"), 10, 6, 60.0,
                List.of(new DistractorShare("B", "选项B", 4, 40.0, new NodeRef("KT1", "变量"))));
        when(service.questionErrorStats(eq(10L), isNull(), anyInt())).thenReturn(
                new QuestionErrorStats(30, 5, List.of(q), null));

        ToolExecutionResult r = tools.get(4).executeForGovernor("c1", teacher, args("{}"));

        assertThat(r.insightCard().columns()).hasSize(5);
        assertThat(r.insightCard().rows().get(0)).containsExactly("题干", "循环", "6/10", "60%", "B 40%→变量");
        verify(service).questionErrorStats(10L, null, 5);
    }

    @Test
    void activityTrendCardIsCappedAtListMaxRowsButJsonKeepsAllDays() throws Exception {
        List<DayStat> days = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            days.add(new DayStat(String.format("2026-09-%02d", i), i, 1, 0));
        }
        when(service.activityTrend(10L, 30)).thenReturn(new ActivityTrend(30, 4, days, 465, null));

        ToolExecutionResult r = tools.get(5).executeForGovernor("c1", teacher, args("{\"days\":30}"));

        assertThat(r.insightCard().rows()).hasSize(TeacherAnalyticsService.LIST_MAX_ROWS);
        assertThat(r.insightCard().rows().get(19).get(0)).isEqualTo("2026-09-30");
        assertThat(r.insightCard().note()).contains("最近 20 天");
        assertThat(om.readTree(r.content()).path("daily")).hasSize(30);
    }
}
