package com.wenjin.service.memory;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.service.AiJobService;
import com.wenjin.support.AiJobKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryJobEnqueueServiceTest {

    @Mock private AiJobService aiJobService;

    private FeatureFlagsProperties flags;
    private MemoryJobEnqueueService service;

    @BeforeEach
    void setUp() {
        flags = new FeatureFlagsProperties();
        flags.setMemoryEnabled(true);
        flags.setAiJobDispatchEnabled(true);
        service = new MemoryJobEnqueueService(aiJobService, flags);
    }

    @Test
    @DisplayName("memory-enabled=true → 派发 MEMORY_DISTILL + SUMMARY_REFRESH")
    void memoryOn_enqueuesBoth() {
        when(aiJobService.enqueueInternal(anyLong(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(null);

        service.onSummaryUpdated(conv(), 80L);

        verify(aiJobService).enqueueInternal(
                eq(2L), eq(1L), eq(AiJobKind.MEMORY_DISTILL),
                eq("conv:10:through:80"), any());
        verify(aiJobService).enqueueInternal(
                eq(2L), eq(1L), eq(AiJobKind.SUMMARY_REFRESH),
                eq("conv:10:summary:80"), any());
    }

    @Test
    @DisplayName("memory-enabled=false → 不派发 MEMORY_DISTILL，仍可派发 SUMMARY_REFRESH")
    void memoryOff_skipsDistillOnly() {
        flags.setMemoryEnabled(false);
        when(aiJobService.enqueueInternal(anyLong(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(null);

        service.onSummaryUpdated(conv(), 80L);

        verify(aiJobService, never()).enqueueInternal(
                anyLong(), anyLong(), eq(AiJobKind.MEMORY_DISTILL), anyString(), any());
        verify(aiJobService, times(1)).enqueueInternal(
                eq(2L), eq(1L), eq(AiJobKind.SUMMARY_REFRESH),
                eq("conv:10:summary:80"), any());
    }

    private static CompanionConversation conv() {
        CompanionConversation c = new CompanionConversation();
        c.setId(10L);
        c.setStudentId(1L);
        c.setCourseId(2L);
        return c;
    }
}
