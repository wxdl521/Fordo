package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.InsightCard;
import com.wenjin.service.trace.TraceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 方向 7 契约第 4 条：本轮提供工具名单 + 受众校验（学生侧同样生效）。
 */
class ToolGovernorAudienceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("学生会话：模型编造已注册但本轮未提供的学生工具名 → GUARD TOOL_NOT_ALLOWED，工具体不执行，占预算")
    void studentSession_registeredButNotOfferedTool_isRejected() throws Exception {
        AtomicInteger offeredRuns = new AtomicInteger();
        AtomicInteger hiddenRuns = new AtomicInteger();
        ToolGovernor governor = governor(List.of(
                new Tool("query_mastery", ToolAudience.STUDENT, offeredRuns),
                new Tool("search_materials", ToolAudience.STUDENT, hiddenRuns)));
        ToolExecutionState state = new ToolExecutionState(3, Set.of("query_mastery"));

        ToolExecutionResult result = governor.execute(new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("c1", "search_materials", "{}"), state);

        assertThat(result.ok()).isFalse();
        assertThat(result.guarded()).isTrue();
        assertThat(code(result)).isEqualTo(ToolErrorCodes.TOOL_NOT_ALLOWED);
        assertThat(hiddenRuns.get()).isZero();
        assertThat(state.used()).isEqualTo(1);

        ToolExecutionResult ok = governor.execute(new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("c2", "query_mastery", "{}"), state);
        assertThat(ok.ok()).isTrue();
        assertThat(offeredRuns.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("学生会话：模型编造老师工具名（即使误入提供名单）→ 受众不符 TOOL_NOT_ALLOWED")
    void studentSession_teacherTool_isRejectedByAudience() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        ToolGovernor governor = governor(List.of(new Tool("get_class_overview", ToolAudience.TEACHER, runs)));
        // 名单校验之外的第二道闸：即使名单含该名，受众不符仍拒绝
        ToolExecutionState state = new ToolExecutionState(3, Set.of("get_class_overview"));

        ToolExecutionResult result = governor.execute(new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("c1", "get_class_overview", "{}"), state);

        assertThat(result.guarded()).isTrue();
        assertThat(code(result)).isEqualTo(ToolErrorCodes.TOOL_NOT_ALLOWED);
        assertThat(runs.get()).isZero();
    }

    @Test
    @DisplayName("老师会话：学生工具名 → 受众不符 TOOL_NOT_ALLOWED；老师工具正常执行")
    void teacherSession_studentToolRejected_teacherToolRuns() throws Exception {
        AtomicInteger studentRuns = new AtomicInteger();
        AtomicInteger teacherRuns = new AtomicInteger();
        ToolGovernor governor = governor(List.of(
                new Tool("recall_student_context", ToolAudience.STUDENT, studentRuns),
                new Tool("get_class_overview", ToolAudience.TEACHER, teacherRuns)));
        ToolContext teacher = ToolContext.forTeacher(7L, 10L);
        ToolExecutionState state = new ToolExecutionState(4,
                Set.of("recall_student_context", "get_class_overview"));

        ToolExecutionResult rejected = governor.execute(teacher,
                new CompanionAiClient.ToolCall("c1", "recall_student_context", "{}"), state);
        ToolExecutionResult ok = governor.execute(teacher,
                new CompanionAiClient.ToolCall("c2", "get_class_overview", "{}"), state);

        assertThat(code(rejected)).isEqualTo(ToolErrorCodes.TOOL_NOT_ALLOWED);
        assertThat(studentRuns.get()).isZero();
        assertThat(ok.ok()).isTrue();
        assertThat(teacherRuns.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("拒绝文案对『未提供』与『不存在』一致（不泄露工具是否存在）")
    void rejectionMessage_sameForUnknownAndNotOffered() throws Exception {
        ToolGovernor governor = governor(List.of(new Tool("get_class_overview", ToolAudience.TEACHER, new AtomicInteger())));
        ToolExecutionState state = new ToolExecutionState(5, Set.of());
        ToolExecutionResult notOffered = governor.execute(new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("c1", "get_class_overview", "{}"), state);
        ToolExecutionResult unknown = governor.execute(new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("c2", "no_such_tool", "{}"), state);

        assertThat(message(notOffered)).isEqualTo("工具未授权或不存在: get_class_overview");
        assertThat(message(unknown)).isEqualTo("工具未授权或不存在: no_such_tool");
    }

    @Test
    @DisplayName("未声明提供名单（旧构造器）时只做受众校验，保持旧调用方行为")
    void legacyState_withoutOfferedNames_onlyAudienceChecked() {
        AtomicInteger runs = new AtomicInteger();
        ToolGovernor governor = governor(List.of(new Tool("query_mastery", ToolAudience.STUDENT, runs)));
        ToolExecutionResult ok = governor.execute(new ToolContext(1L, 10L),
                new CompanionAiClient.ToolCall("c1", "query_mastery", "{}"), new ToolExecutionState(3));
        assertThat(ok.ok()).isTrue();
    }

    @Test
    @DisplayName("readCacheable：默认沿用 READ_CACHEABLE_TOOLS 名单（旧行为不变）")
    void readCacheable_defaultFollowsLegacySet() {
        assertThat(new Tool("query_mastery", ToolAudience.STUDENT, new AtomicInteger()).readCacheable()).isTrue();
        assertThat(new Tool("start_practice", ToolAudience.STUDENT, new AtomicInteger()).readCacheable()).isFalse();
        assertThat(new CacheableTeacherTool(new AtomicInteger()).readCacheable()).isTrue();
    }

    @Test
    @DisplayName("老师工具可缓存：同一 run 相同参数第二次命中缓存，不再执行、不占预算")
    void teacherCacheableTool_hitsCache() {
        AtomicInteger runs = new AtomicInteger();
        CacheableTeacherTool tool = new CacheableTeacherTool(runs);
        ToolGovernor governor = governor(List.of(tool));
        ToolContext teacher = ToolContext.forTeacher(7L, 10L);
        TraceContext trace = freshTrace();
        ToolExecutionState state = new ToolExecutionState(5, Set.of(tool.name()));

        governor.execute(teacher, new CompanionAiClient.ToolCall("c1", tool.name(), "{}"), state, trace);
        ToolExecutionResult second = governor.execute(teacher,
                new CompanionAiClient.ToolCall("c2", tool.name(), "{}"), state, trace);

        assertThat(second.cached()).isTrue();
        assertThat(runs.get()).isEqualTo(1);
        assertThat(state.used()).isEqualTo(1);
    }

    @Test
    @DisplayName("insightCard 经 governor 规范化后保留")
    void insightCard_survivesNormalization() {
        InsightCard card = new InsightCard("关注名单", List.of("学生"), List.of(List.of("张三")), null);
        CompanionTool tool = new CompanionTool() {
            @Override
            public String name() {
                return "list_students_needing_attention";
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "function", "function", Map.of("name", name()));
            }

            @Override
            public ToolAudience audience() {
                return ToolAudience.TEACHER;
            }

            @Override
            public String execute(ToolContext ctx, JsonNode args) {
                return "{}";
            }

            @Override
            public ToolExecutionResult executeForGovernor(String toolCallId, ToolContext ctx, JsonNode args) {
                return new ToolExecutionResult(toolCallId, name(), true, "{\"n\":1}", null, false, false, null, card);
            }
        };
        ToolGovernor governor = governor(List.of(tool));

        ToolExecutionResult result = governor.execute(ToolContext.forTeacher(7L, 10L),
                new CompanionAiClient.ToolCall("c1", tool.name(), "{}"),
                new ToolExecutionState(3, Set.of(tool.name())));

        assertThat(result.ok()).isTrue();
        assertThat(result.insightCard()).isEqualTo(card);
    }

    // ── helpers ──

    private static TraceContext freshTrace() {
        return TraceContext.active(1L);
    }

    private ToolGovernor governor(List<CompanionTool> tools) {
        CompanionToolProperties properties = new CompanionToolProperties();
        properties.setEnabled(true);
        properties.setTimeoutMs(5_000);
        return new ToolGovernor(tools, objectMapper, properties, new LimitsProperties());
    }

    private String code(ToolExecutionResult result) throws Exception {
        return objectMapper.readTree(result.content()).path("code").asText();
    }

    private String message(ToolExecutionResult result) throws Exception {
        return objectMapper.readTree(result.content()).path("message").asText();
    }

    private record Tool(String name, ToolAudience audience, AtomicInteger runs) implements CompanionTool {
        @Override
        public Map<String, Object> schema() {
            return Map.of("type", "function", "function", Map.of("name", name));
        }

        @Override
        public boolean sideEffecting() {
            return "start_practice".equals(name);
        }

        @Override
        public String execute(ToolContext ctx, JsonNode args) {
            runs.incrementAndGet();
            return "{\"ok\":true}";
        }
    }

    private record CacheableTeacherTool(AtomicInteger runs) implements CompanionTool {
        @Override
        public String name() {
            return "get_class_overview";
        }

        @Override
        public Map<String, Object> schema() {
            return Map.of("type", "function", "function", Map.of("name", name()));
        }

        @Override
        public ToolAudience audience() {
            return ToolAudience.TEACHER;
        }

        @Override
        public boolean readCacheable() {
            return true;
        }

        @Override
        public String execute(ToolContext ctx, JsonNode args) {
            runs.incrementAndGet();
            return "{\"students\":3}";
        }
    }
}
