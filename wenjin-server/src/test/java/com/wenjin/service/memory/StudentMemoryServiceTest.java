package com.wenjin.service.memory;

import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.StudentMemory;
import com.wenjin.mapper.StudentMemoryMapper;
import com.wenjin.support.MemoryKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentMemoryServiceTest {

    @Mock
    private StudentMemoryMapper memoryMapper;

    private StudentMemoryService service;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneOffset.UTC);
        LimitsProperties limits = new LimitsProperties();
        MemoryStrengthCalculator strength = new MemoryStrengthCalculator(clock, limits);
        service = new StudentMemoryService(memoryMapper, new MemoryJaccardDeduper(), strength, limits);
    }

    @Test
    @DisplayName("Jaccard 同义 → reinforce 已有，不 insert")
    void synonym_reinforcesExisting() {
        StudentMemory existing = new StudentMemory();
        existing.setId(42L);
        existing.setContent("alpha beta gamma");
        existing.setKind(MemoryKind.EPISODIC);
        when(memoryMapper.selectActive(1L, 2L, MemoryKind.EPISODIC)).thenReturn(List.of(existing));
        when(memoryMapper.reinforce(eq(42L), any(LocalDateTime.class))).thenReturn(1);

        Long id = service.upsertOrReinforce(
                1L, 2L, MemoryKind.EPISODIC,
                "alpha beta gamma delta epsilon", null, null);

        assertThat(id).isEqualTo(42L);
        verify(memoryMapper).reinforce(eq(42L), any(LocalDateTime.class));
        verify(memoryMapper, never()).insert(any(StudentMemory.class));
    }

    @Test
    @DisplayName("非同义 → insert 新行")
    void distinct_insertsNew() {
        when(memoryMapper.selectActive(1L, 2L, MemoryKind.SEMANTIC)).thenReturn(List.of());
        when(memoryMapper.insert(any(StudentMemory.class))).thenAnswer(inv -> {
            StudentMemory m = inv.getArgument(0);
            m.setId(99L);
            return 1;
        });

        Long id = service.upsertOrReinforce(
                1L, 2L, MemoryKind.SEMANTIC, "Dijkstra 最短路径", "N1", 7L);

        assertThat(id).isEqualTo(99L);
        ArgumentCaptor<StudentMemory> captor = ArgumentCaptor.forClass(StudentMemory.class);
        verify(memoryMapper).insert(captor.capture());
        StudentMemory created = captor.getValue();
        assertThat(created.getContent()).isEqualTo("Dijkstra 最短路径");
        assertThat(created.getNodeCode()).isEqualTo("N1");
        assertThat(created.getSourceRunId()).isEqualTo(7L);
        assertThat(created.getSalience()).isEqualByComparingTo(BigDecimal.ONE.setScale(4));
        assertThat(created.getLastReinforcedAt())
                .isEqualTo(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}
