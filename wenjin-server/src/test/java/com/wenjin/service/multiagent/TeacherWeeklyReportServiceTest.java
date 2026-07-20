package com.wenjin.service.multiagent;

import com.wenjin.entity.AgentReport;
import com.wenjin.support.AgentReportKind;
import com.wenjin.support.AgentReportStatus;
import com.wenjin.support.multiagent.ReportAggregations.EffectiveDelta;
import com.wenjin.support.multiagent.ReportAggregations.NodeStudentEffective;
import com.wenjin.support.multiagent.ReportAggregations.StudentLastPractice;
import com.wenjin.support.multiagent.ReportNarrativeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherWeeklyReportServiceTest {

    @Mock AgentReportService agentReportService;

    private TeacherWeeklyReportService service;

    @BeforeEach
    void setUp() {
        service = new TeacherWeeklyReportService(agentReportService, new ReportNarrativeGenerator.NoOp());
    }

    @Test
    @DisplayName("build payload READY even when narrative is empty (NoOp)")
    void narrativeEmpty_stillReady() {
        AgentReport pending = new AgentReport();
        pending.setId(55L);
        pending.setStatus(AgentReportStatus.PENDING);
        when(agentReportService.ensurePending(anyLong(), anyInt(), any(), any())).thenReturn(pending);

        LocalDate start = LocalDate.of(2026, 7, 13);
        LocalDate end = LocalDate.of(2026, 7, 19);
        long id = service.buildAndStore(
                1L, start, end,
                List.of(new NodeStudentEffective(1L, "N1", 0.2)),
                List.of(new EffectiveDelta(1L, "N1", 0.9, 0.5)),
                List.of(new StudentLastPractice(2L, null)),
                4, 2, 3, 1
        );

        assertThat(id).isEqualTo(55L);
        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(agentReportService).markReady(eq(55L), payloadCap.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCap.getValue();
        assertThat(payload.get("narrative")).isEqualTo("");
        assertThat(payload.get("weakNodes")).isInstanceOf(List.class);
        assertThat(payload.get("anomalies")).isInstanceOf(List.class);
        assertThat(payload.get("engagement")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("narrative generator failure still marks READY with empty narrative")
    void narrativeThrows_stillReady() {
        AgentReport pending = new AgentReport();
        pending.setId(9L);
        pending.setStatus(AgentReportStatus.PENDING);
        when(agentReportService.ensurePending(anyLong(), anyInt(), any(), any())).thenReturn(pending);
        service = new TeacherWeeklyReportService(agentReportService, s -> {
            throw new RuntimeException("llm down");
        });

        service.buildEmptySkeleton(1L, LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));

        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(agentReportService).markReady(eq(9L), payloadCap.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCap.getValue();
        assertThat(payload.get("narrative")).isEqualTo("");
    }

    @Test
    @DisplayName("already READY report is not rebuilt (idempotent)")
    void alreadyReady_skipsRebuild() {
        AgentReport ready = new AgentReport();
        ready.setId(3L);
        ready.setStatus(AgentReportStatus.READY);
        ready.setPayloadJson("{\"weakNodes\":[]}");
        when(agentReportService.ensurePending(
                eq(1L), eq(AgentReportKind.TEACHER_WEEKLY), any(), any())).thenReturn(ready);

        long id = service.buildEmptySkeleton(1L, LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));
        assertThat(id).isEqualTo(3L);
        verify(agentReportService, never()).markReady(anyLong(), any());
    }
}
