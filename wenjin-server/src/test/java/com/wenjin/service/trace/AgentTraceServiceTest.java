package com.wenjin.service.trace;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.AgentRun;
import com.wenjin.entity.AgentStep;
import com.wenjin.mapper.AgentRunMapper;
import com.wenjin.mapper.AgentStepMapper;
import com.wenjin.support.AgentRunStatus;
import com.wenjin.support.AgentStepStatus;
import com.wenjin.support.AgentStepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTraceServiceTest {

    @Mock AgentRunMapper agentRunMapper;
    @Mock AgentStepMapper agentStepMapper;

    private FeatureFlagsProperties featureFlags;
    private LimitsProperties limits;
    private AgentTraceService service;
    private final AtomicLong idSeq = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        featureFlags = new FeatureFlagsProperties();
        featureFlags.setAgentTraceEnabled(true);
        limits = new LimitsProperties();
        limits.getTrace().setMaxIoChars(8000);
        service = new AgentTraceService(agentRunMapper, agentStepMapper, featureFlags, limits);
    }

    @Test
    @DisplayName("toggle off → startRun returns NOOP; step/finish never write")
    void noop_whenToggleOff() {
        featureFlags.setAgentTraceEnabled(false);

        TraceContext ctx = service.startRun(StartRunCmd.of(1L, 2L, null, "COMPANION_CHAT", "m", "hi"));

        assertThat(ctx).isSameAs(TraceContext.NOOP);
        assertThat(ctx.isNoop()).isTrue();
        verify(agentRunMapper, never()).insert(any(AgentRun.class));

        service.step(ctx, sampleStep("short-in", "short-out"));
        service.finishRun(ctx, AgentRunStatus.SUCCEEDED, null);

        verify(agentStepMapper, never()).insert(any(AgentStep.class));
        verify(agentRunMapper, never()).updateById(any(AgentRun.class));
    }

    @Test
    @DisplayName("startRun inserts RUNNING row and returns active context with id")
    void startRun_insertsAndReturnsActiveCtx() {
        when(agentRunMapper.insert(any(AgentRun.class))).thenAnswer(inv -> {
            AgentRun run = inv.getArgument(0);
            run.setId(idSeq.getAndIncrement());
            return 1;
        });

        String longInput = "x".repeat(250);
        TraceContext ctx = service.startRun(new StartRunCmd(
                10L, 20L, 30L, "COMPANION_CHAT", "deepseek", longInput, "req-abc"));

        assertThat(ctx.isNoop()).isFalse();
        assertThat(ctx.runId()).isEqualTo(100L);

        ArgumentCaptor<AgentRun> cap = ArgumentCaptor.forClass(AgentRun.class);
        verify(agentRunMapper).insert(cap.capture());
        AgentRun saved = cap.getValue();
        assertThat(saved.getStudentId()).isEqualTo(10L);
        assertThat(saved.getCourseId()).isEqualTo(20L);
        assertThat(saved.getConversationId()).isEqualTo(30L);
        assertThat(saved.getKind()).isEqualTo("COMPANION_CHAT");
        assertThat(saved.getModel()).isEqualTo("deepseek");
        assertThat(saved.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(saved.getRequestId()).isEqualTo("req-abc");
        assertThat(saved.getInputDigest()).hasSize(AgentTraceService.INPUT_DIGEST_MAX);
        assertThat(saved.getTokensIn()).isZero();
        assertThat(saved.getTokensOut()).isZero();
    }

    @Test
    @DisplayName("step increments seq, truncates oversize I/O, aggregates tokens")
    void step_truncatesAndAggregates() {
        when(agentRunMapper.insert(any(AgentRun.class))).thenAnswer(inv -> {
            AgentRun run = inv.getArgument(0);
            run.setId(42L);
            return 1;
        });
        when(agentStepMapper.insert(any(AgentStep.class))).thenReturn(1);

        limits.getTrace().setMaxIoChars(10);
        TraceContext ctx = service.startRun(StartRunCmd.of(1L, 2L, null, "COMPANION_CHAT", null, "hi"));

        String oversize = "abcdefghijklmnop"; // 16 chars
        service.step(ctx, new StepRecord(
                AgentStepType.LLM_CALL, "phase1", AgentStepStatus.OK,
                oversize, oversize, 11, 22, 5, null));
        service.step(ctx, new StepRecord(
                AgentStepType.TOOL_CALL, "query_mastery", AgentStepStatus.OK,
                "short", "ok", 3, 4, 1, null));

        ArgumentCaptor<AgentStep> cap = ArgumentCaptor.forClass(AgentStep.class);
        verify(agentStepMapper, times(2)).insert(cap.capture());

        AgentStep first = cap.getAllValues().get(0);
        assertThat(first.getRunId()).isEqualTo(42L);
        assertThat(first.getSeq()).isEqualTo(1);
        assertThat(first.getInputJson())
                .isEqualTo("abcdefghij" + AgentTraceService.TRUNCATED_MARKER);
        assertThat(first.getOutputJson())
                .isEqualTo("abcdefghij" + AgentTraceService.TRUNCATED_MARKER);
        assertThat(first.getTokensIn()).isEqualTo(11);
        assertThat(first.getTokensOut()).isEqualTo(22);

        AgentStep second = cap.getAllValues().get(1);
        assertThat(second.getSeq()).isEqualTo(2);
        assertThat(second.getInputJson()).isEqualTo("short");
        assertThat(second.getOutputJson()).isEqualTo("ok");

        assertThat(ctx.tokensIn()).isEqualTo(14);
        assertThat(ctx.tokensOut()).isEqualTo(26);
        assertThat(ctx.currentSeq()).isEqualTo(2);
    }

    @Test
    @DisplayName("finishRun writes terminal status, aggregates, duration, finishedAt")
    void finishRun_updatesAggregates() {
        when(agentRunMapper.insert(any(AgentRun.class))).thenAnswer(inv -> {
            AgentRun run = inv.getArgument(0);
            run.setId(7L);
            return 1;
        });
        when(agentStepMapper.insert(any(AgentStep.class))).thenReturn(1);
        when(agentRunMapper.updateById(any(AgentRun.class))).thenReturn(1);

        TraceContext ctx = service.startRun(StartRunCmd.of(1L, 2L, null, "DIAGNOSTIC", "m", null));
        service.step(ctx, new StepRecord(
                AgentStepType.LLM_CALL, "phase1", AgentStepStatus.OK,
                null, null, 100, 200, 10, null));
        service.finishRun(ctx, AgentRunStatus.SUCCEEDED, null);

        ArgumentCaptor<AgentRun> cap = ArgumentCaptor.forClass(AgentRun.class);
        verify(agentRunMapper).updateById(cap.capture());
        AgentRun patch = cap.getValue();
        assertThat(patch.getId()).isEqualTo(7L);
        assertThat(patch.getStatus()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(patch.getTokensIn()).isEqualTo(100);
        assertThat(patch.getTokensOut()).isEqualTo(200);
        assertThat(patch.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(patch.getFinishedAt()).isNotNull();
        assertThat(patch.getErrorCode()).isNull();
    }

    @Test
    @DisplayName("mapper throws → startRun/step/finish never throw; start falls back to NOOP")
    void exceptions_swallowed() {
        when(agentRunMapper.insert(any(AgentRun.class)))
                .thenThrow(new RuntimeException("db down"));

        TraceContext ctx = service.startRun(StartRunCmd.of(1L, 2L, null, "COMPANION_CHAT", null, "x"));
        assertThat(ctx).isSameAs(TraceContext.NOOP);
        assertThatCode(() -> service.step(ctx, sampleStep("a", "b"))).doesNotThrowAnyException();
        assertThatCode(() -> service.finishRun(ctx, AgentRunStatus.FAILED, "ERR")).doesNotThrowAnyException();
        verify(agentStepMapper, never()).insert(any(AgentStep.class));
        verify(agentRunMapper, never()).updateById(any(AgentRun.class));
    }

    @Test
    @DisplayName("active ctx: step/finish swallow mapper failures")
    void activeCtx_stepAndFinishSwallowExceptions() {
        when(agentRunMapper.insert(any(AgentRun.class))).thenAnswer(inv -> {
            AgentRun run = inv.getArgument(0);
            run.setId(9L);
            return 1;
        });
        when(agentStepMapper.insert(any(AgentStep.class)))
                .thenThrow(new RuntimeException("step insert fail"));
        when(agentRunMapper.updateById(any(AgentRun.class)))
                .thenThrow(new RuntimeException("finish update fail"));

        TraceContext ctx = service.startRun(StartRunCmd.of(1L, 2L, null, "COMPANION_CHAT", null, "x"));
        assertThat(ctx.isNoop()).isFalse();

        assertThatCode(() -> service.step(ctx, sampleStep("in", "out"))).doesNotThrowAnyException();
        assertThatCode(() -> service.finishRun(ctx, AgentRunStatus.FAILED, "X")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("write methods declare @Transactional(REQUIRES_NEW)")
    void writeMethods_requireNewPropagation() throws Exception {
        assertRequiresNew("startRun", StartRunCmd.class);
        assertRequiresNew("step", TraceContext.class, StepRecord.class);
        assertRequiresNew("finishRun", TraceContext.class, int.class, String.class);
    }

    @Test
    @DisplayName("truncateIo leaves short strings untouched; appends marker when oversize")
    void truncateIo_unit() {
        assertThat(service.truncateIo(null)).isNull();
        assertThat(service.truncateIo("abc")).isEqualTo("abc");
        limits.getTrace().setMaxIoChars(3);
        assertThat(service.truncateIo("abcdef"))
                .isEqualTo("abc" + AgentTraceService.TRUNCATED_MARKER);
    }

    @Test
    @DisplayName("input_digest is first 200 chars only")
    void inputDigest_first200() {
        assertThat(AgentTraceService.toInputDigest(null)).isNull();
        assertThat(AgentTraceService.toInputDigest("short")).isEqualTo("short");
        String s = "y".repeat(201);
        assertThat(AgentTraceService.toInputDigest(s)).isEqualTo("y".repeat(200));
    }

    private void assertRequiresNew(String methodName, Class<?>... paramTypes) throws Exception {
        Method m = AgentTraceService.class.getMethod(methodName, paramTypes);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx).as("@Transactional on %s", methodName).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private static StepRecord sampleStep(String in, String out) {
        return new StepRecord(
                AgentStepType.LLM_CALL, "phase1", AgentStepStatus.OK,
                in, out, 0, 0, null, null);
    }
}
