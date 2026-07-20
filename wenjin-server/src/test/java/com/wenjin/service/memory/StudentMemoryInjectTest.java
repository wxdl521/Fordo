package com.wenjin.service.memory;

import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.StudentMemory;
import com.wenjin.mapper.StudentMemoryMapper;
import com.wenjin.support.MemoryKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentMemoryInjectTest {

    @Mock
    private StudentMemoryMapper memoryMapper;

    private StudentMemoryService service;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneOffset.UTC);
        LimitsProperties limits = new LimitsProperties();
        limits.getMemory().setInjectTopK(5);
        MemoryStrengthCalculator strength = new MemoryStrengthCalculator(clock, limits);
        service = new StudentMemoryService(memoryMapper, new MemoryJaccardDeduper(), strength, limits);
    }

    @Test
    @DisplayName("formatInjectLines groups by kind with section headers")
    void format_groupsByKind() {
        List<String> lines = service.formatInjectLines(List.of(
                mem(1L, MemoryKind.SEMANTIC, "偏好代码示例", null),
                mem(2L, MemoryKind.EPISODIC, "二叉树连错3次", "SE-4.1"),
                mem(3L, MemoryKind.PROCEDURAL, "分步拆解有效", "SE-3.2")));

        assertThat(lines).containsExactly(
                "【学生画像】",
                "· 偏好代码示例",
                "【近期学习经历】",
                "· 二叉树连错3次（SE-4.1）",
                "【有效策略】",
                "· 分步拆解有效（SE-3.2）");
    }

    @Test
    @DisplayName("loadInjectLines takes top-k by effective and reinforces")
    void loadInject_topK_andReinforce() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        StudentMemory strong = mem(10L, MemoryKind.SEMANTIC, "强记忆", null);
        strong.setSalience(BigDecimal.valueOf(1.5));
        strong.setLastReinforcedAt(now);
        StudentMemory weak = mem(11L, MemoryKind.EPISODIC, "弱记忆", "N1");
        weak.setSalience(BigDecimal.valueOf(0.5));
        weak.setLastReinforcedAt(now.minusDays(30));
        StudentMemory mid = mem(12L, MemoryKind.PROCEDURAL, "中记忆", null);
        mid.setSalience(BigDecimal.ONE);
        mid.setLastReinforcedAt(now.minusDays(1));

        when(memoryMapper.selectActive(1L, 2L, null)).thenReturn(List.of(weak, mid, strong));
        when(memoryMapper.reinforce(any(), any())).thenReturn(1);

        LimitsProperties limits = new LimitsProperties();
        limits.getMemory().setInjectTopK(2);
        service = new StudentMemoryService(
                memoryMapper, new MemoryJaccardDeduper(),
                new MemoryStrengthCalculator(clock, limits), limits);

        List<String> lines = service.loadInjectLines(1L, 2L);

        assertThat(lines).isNotEmpty();
        assertThat(String.join("\n", lines)).contains("强记忆").contains("中记忆").doesNotContain("弱记忆");
        verify(memoryMapper).reinforce(eq(10L), any());
        verify(memoryMapper).reinforce(eq(12L), any());
        verify(memoryMapper, times(2)).reinforce(any(), any());
    }

    @Test
    @DisplayName("searchAndReinforce uses LIKE results, effective order, reinforces hits")
    void search_ordersAndReinforces() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        StudentMemory a = mem(1L, MemoryKind.SEMANTIC, "代码示例偏好", null);
        a.setSalience(BigDecimal.valueOf(1.2));
        a.setLastReinforcedAt(now);
        StudentMemory b = mem(2L, MemoryKind.EPISODIC, "代码练习失败", "SE-1");
        b.setSalience(BigDecimal.valueOf(0.8));
        b.setLastReinforcedAt(now.minusDays(10));

        when(memoryMapper.selectByContentLike(1L, 2L, "代码", null)).thenReturn(List.of(b, a));
        when(memoryMapper.reinforce(any(), any())).thenReturn(1);

        List<StudentMemory> hits = service.searchAndReinforce(1L, 2L, "代码", null, 5);

        assertThat(hits).extracting(StudentMemory::getId).containsExactly(1L, 2L);
        verify(memoryMapper).reinforce(eq(1L), any());
        verify(memoryMapper).reinforce(eq(2L), any());
    }

    @Test
    @DisplayName("upsertOrReinforce accepts custom salience")
    void upsert_customSalience() {
        when(memoryMapper.selectActive(1L, 2L, MemoryKind.SEMANTIC)).thenReturn(List.of());
        when(memoryMapper.insert(any(StudentMemory.class))).thenAnswer(inv -> {
            StudentMemory m = inv.getArgument(0);
            m.setId(7L);
            return 1;
        });

        Long id = service.upsertOrReinforce(
                1L, 2L, MemoryKind.SEMANTIC, "观察一条", null, null,
                BigDecimal.valueOf(1.4));

        assertThat(id).isEqualTo(7L);
        org.mockito.ArgumentCaptor<StudentMemory> cap =
                org.mockito.ArgumentCaptor.forClass(StudentMemory.class);
        verify(memoryMapper).insert(cap.capture());
        assertThat(cap.getValue().getSalience()).isEqualByComparingTo(BigDecimal.valueOf(1.4).setScale(4));
    }

    private static StudentMemory mem(Long id, int kind, String content, String nodeCode) {
        StudentMemory m = new StudentMemory();
        m.setId(id);
        m.setKind(kind);
        m.setContent(content);
        m.setNodeCode(nodeCode);
        m.setSalience(BigDecimal.ONE);
        m.setLastReinforcedAt(LocalDateTime.of(2026, 7, 10, 12, 0));
        return m;
    }
}
