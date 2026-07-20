package com.wenjin.service.plan;

import com.wenjin.entity.AgentRun;
import com.wenjin.mapper.AgentRunMapper;
import com.wenjin.service.trace.TraceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanCancelGuardTest {

    @Mock AgentRunMapper agentRunMapper;

    @Test
    @DisplayName("requestCancel 幂等置位 cancel_requested_at")
    void requestCancel() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-01T12:00:00Z"), ZoneOffset.UTC);
        PlanCancelGuard guard = new PlanCancelGuard(agentRunMapper, clock);

        AgentRun run = new AgentRun();
        run.setId(5L);
        run.setStudentId(1L);
        when(agentRunMapper.selectById(5L)).thenReturn(run);

        assertThat(guard.requestCancel(5L)).isTrue();
        ArgumentCaptor<AgentRun> cap = ArgumentCaptor.forClass(AgentRun.class);
        verify(agentRunMapper).updateById(cap.capture());
        assertThat(cap.getValue().getCancelRequestedAt()).isNotNull();
    }

    @Test
    @DisplayName("isCancelRequested 读取 cancel_requested_at")
    void isCancelRequested() {
        PlanCancelGuard guard = new PlanCancelGuard(agentRunMapper, Clock.systemUTC());
        AgentRun run = new AgentRun();
        run.setId(3L);
        run.setCancelRequestedAt(LocalDateTime.now());
        when(agentRunMapper.selectById(3L)).thenReturn(run);

        assertThat(guard.isCancelRequested(3L)).isTrue();
        assertThat(guard.isCancelRequested(TraceContext.active(3L))).isTrue();
        assertThat(guard.isCancelRequested(TraceContext.NOOP)).isFalse();
    }
}
