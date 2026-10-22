package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.InsightCard;
import com.wenjin.service.CompanionService.CompanionStreamSink;
import com.wenjin.service.trace.StepRecord;
import com.wenjin.support.AgentStepType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 方向 7：编排层的工具泄漏加固回归（真实 ToolGovernor）+ insight_card 事件顺序。
 */
class CompanionOrchestratorAudienceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("学生会话：模型编造老师工具名与未提供的学生工具名 → 两者均 GUARD 拦截、工具体不执行")
    void studentTurn_fabricatedToolNames_areGuarded() {
        AtomicInteger teacherRuns = new AtomicInteger();
        AtomicInteger hiddenStudentRuns = new AtomicInteger();
        AtomicInteger offeredRuns = new AtomicInteger();
        CompanionTool offered = tool("query_mastery", ToolAudience.STUDENT, offeredRuns, null);
        CompanionTool hidden = tool("search_materials", ToolAudience.STUDENT, hiddenStudentRuns, null);
        CompanionTool teacherTool = tool("list_students_needing_attention", ToolAudience.TEACHER, teacherRuns, null);
        ToolGovernor governor = governor(offered, hidden, teacherTool);

        CompanionAiClient ai = Mockito.mock(CompanionAiClient.class);
        when(ai.streamTurn(anyList(), anyList(), any(), any()))
                .thenReturn(new CompanionAiClient.StreamTurnResult("", List.of(
                        new CompanionAiClient.ToolCall("c1", "list_students_needing_attention", "{}"),
                        new CompanionAiClient.ToolCall("c2", "search_materials", "{}"),
                        new CompanionAiClient.ToolCall("c3", "query_mastery", "{}")), null, "tool_calls"))
                .thenReturn(new CompanionAiClient.StreamTurnResult("好的", List.of(), null, "stop"));

        List<StepRecord> steps = new ArrayList<>();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CompanionOrchestrator orch = new CompanionOrchestrator(ai, governor, shell(steps));

        orch.run(new CompanionOrchestrator.Request(List.of(Map.of("role", "user", "content", "hi")),
                List.of(offered), new ToolContext(1L, 10L), null, 3, 6, 1000, "", null, null), recordingSink(events));

        assertThat(teacherRuns.get()).isZero();
        assertThat(hiddenStudentRuns.get()).isZero();
        assertThat(offeredRuns.get()).isEqualTo(1);
        List<StepRecord> guards = steps.stream().filter(s -> AgentStepType.GUARD.equals(s.stepType())).toList();
        assertThat(guards).extracting(StepRecord::name)
                .containsExactly("list_students_needing_attention", "search_materials");
        assertThat(guards).extracting(StepRecord::errorCode)
                .containsOnly(ToolErrorCodes.TOOL_NOT_ALLOWED);
        assertThat(events).contains("tool_result:list_students_needing_attention:false",
                "tool_result:search_materials:false", "tool_result:query_mastery:true");
    }

    @Test
    @DisplayName("老师会话：工具携带 InsightCard → insight_card 紧随其 tool_result 之后、在后续 token 之前；学生工具被拒")
    void teacherTurn_emitsInsightCardAfterToolResult() {
        InsightCard card = new InsightCard("需关注学生", List.of("学生", "平均掌握"),
                List.of(List.of("张三", "38")), null);
        AtomicInteger teacherRuns = new AtomicInteger();
        AtomicInteger studentRuns = new AtomicInteger();
        CompanionTool teacherTool = tool("list_students_needing_attention", ToolAudience.TEACHER, teacherRuns, card);
        CompanionTool studentTool = tool("recall_student_context", ToolAudience.STUDENT, studentRuns, null);
        ToolGovernor governor = governor(teacherTool, studentTool);

        CompanionAiClient ai = Mockito.mock(CompanionAiClient.class);
        when(ai.streamTurn(anyList(), anyList(), any(), any()))
                .thenReturn(new CompanionAiClient.StreamTurnResult("", List.of(
                        new CompanionAiClient.ToolCall("c1", "list_students_needing_attention", "{}"),
                        new CompanionAiClient.ToolCall("c2", "recall_student_context", "{}")), null, "tool_calls"))
                .thenAnswer(inv -> {
                    java.util.function.Consumer<String> onDelta = inv.getArgument(3);
                    onDelta.accept("张三需要关注。");
                    return new CompanionAiClient.StreamTurnResult("张三需要关注。", List.of(), null, "stop");
                });

        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<StepRecord> steps = new ArrayList<>();
        new CompanionOrchestrator(ai, governor, shell(steps)).run(
                new CompanionOrchestrator.Request(List.of(Map.of("role", "user", "content", "谁需要关注")),
                        List.of(teacherTool), ToolContext.forTeacher(9L, 10L), null, 4, 8, 1000, "", null, null),
                recordingSink(events));

        assertThat(teacherRuns.get()).isEqualTo(1);
        assertThat(studentRuns.get()).isZero();
        int result = events.indexOf("tool_result:list_students_needing_attention:true");
        int insight = events.indexOf("insight_card:需关注学生");
        int token = events.indexOf("token:张三需要关注。");
        assertThat(result).isGreaterThanOrEqualTo(0);
        assertThat(insight).isEqualTo(result + 1);
        assertThat(token).isGreaterThan(insight);
        assertThat(events).filteredOn(e -> e.startsWith("insight_card")).hasSize(1);
    }

    @Test
    @DisplayName("老师会话：同轮多个可缓存只读老师工具并行执行，结果按序回放")
    void teacherTurn_readCacheableToolsRunInParallel() {
        java.util.concurrent.CountDownLatch bothStarted = new java.util.concurrent.CountDownLatch(2);
        CompanionTool a = latchTool("get_class_overview", bothStarted);
        CompanionTool b = latchTool("get_activity_trend", bothStarted);
        ToolGovernor governor = governor(a, b);
        CompanionAiClient ai = Mockito.mock(CompanionAiClient.class);
        when(ai.streamTurn(anyList(), anyList(), any(), any()))
                .thenReturn(new CompanionAiClient.StreamTurnResult("", List.of(
                        new CompanionAiClient.ToolCall("c1", "get_class_overview", "{}"),
                        new CompanionAiClient.ToolCall("c2", "get_activity_trend", "{}")), null, "tool_calls"))
                .thenReturn(new CompanionAiClient.StreamTurnResult("ok", List.of(), null, "stop"));
        List<String> events = Collections.synchronizedList(new ArrayList<>());

        new CompanionOrchestrator(ai, governor, shell(new ArrayList<>())).run(
                new CompanionOrchestrator.Request(List.of(Map.of("role", "user", "content", "概况")),
                        List.of(a, b), ToolContext.forTeacher(9L, 10L), null, 4, 8, 1000, "", null, null),
                recordingSink(events));

        // 若串行执行，第一个工具会在 latch 上等到超时并返回 ok=false
        assertThat(events).containsSubsequence(
                "tool_result:get_class_overview:true", "tool_result:get_activity_trend:true");
    }

    // ── helpers ──

    private ToolGovernor governor(CompanionTool... tools) {
        CompanionToolProperties properties = new CompanionToolProperties();
        properties.setEnabled(true);
        properties.setTimeoutMs(3_000);
        return new ToolGovernor(List.of(tools), mapper, properties, new LimitsProperties());
    }

    private static CompanionOrchestrator.Shell shell(List<StepRecord> steps) {
        return new CompanionOrchestrator.Shell() {
            @Override public int degrade(CompanionStreamSink sink, String reason) { return 3; }
            @Override public void streamLegacy(CompanionStreamSink sink) { }
            @Override public String fallbackSystemPrompt() { return ""; }
            @Override public void persist(String text) { }
            @Override public boolean cancelRequested() { return false; }
            @Override public void step(StepRecord record) { synchronized (steps) { steps.add(record); } }
        };
    }

    private static CompanionStreamSink recordingSink(List<String> events) {
        return new CompanionStreamSink() {
            @Override public void token(String token) { events.add("token:" + token); }
            @Override public void toolStart(String name, String label) { events.add("tool_start:" + name); }
            @Override public void toolResult(String name, boolean ok) { events.add("tool_result:" + name + ":" + ok); }
            @Override public void insightCard(InsightCard card) { events.add("insight_card:" + card.title()); }
        };
    }

    private static CompanionTool tool(String name, ToolAudience audience, AtomicInteger runs, InsightCard card) {
        return new CompanionTool() {
            @Override public String name() { return name; }
            @Override public Map<String, Object> schema() {
                return Map.of("type", "function", "function", Map.of("name", name));
            }
            @Override public ToolAudience audience() { return audience; }
            @Override public String execute(ToolContext ctx, JsonNode args) {
                runs.incrementAndGet();
                return "{\"ok\":true}";
            }
            @Override public ToolExecutionResult executeForGovernor(String id, ToolContext ctx, JsonNode args) {
                String content = execute(ctx, args);
                return new ToolExecutionResult(id, name, true, content, null, false, false, null, card);
            }
        };
    }

    private static CompanionTool latchTool(String name, java.util.concurrent.CountDownLatch latch) {
        return new CompanionTool() {
            @Override public String name() { return name; }
            @Override public Map<String, Object> schema() {
                return Map.of("type", "function", "function", Map.of("name", name));
            }
            @Override public ToolAudience audience() { return ToolAudience.TEACHER; }
            @Override public boolean readCacheable() { return true; }
            @Override public String execute(ToolContext ctx, JsonNode args) throws ToolException {
                latch.countDown();
                try {
                    if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new ToolException("not parallel");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ToolException("interrupted");
                }
                return "{\"ok\":true}";
            }
        };
    }
}
