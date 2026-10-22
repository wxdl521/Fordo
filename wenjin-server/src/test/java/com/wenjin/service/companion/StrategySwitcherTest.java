package com.wenjin.service.companion;

import com.wenjin.service.companion.StrategySwitcher.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StrategySwitcherTest {

    @Test
    @DisplayName("deterministic ORDER: first unused is ANALOGY")
    void next_emptyExclusions_analogy() {
        assertThat(StrategySwitcher.next(Set.of(), Set.of())).contains(Strategy.ANALOGY);
        assertThat(StrategySwitcher.remaining(null, null)).isEqualTo(StrategySwitcher.ORDER);
    }

    @Test
    @DisplayName("excludes used ∪ invalid")
    void next_excludesUsedAndInvalid() {
        Set<Strategy> used = EnumSet.of(Strategy.ANALOGY, Strategy.EXAMPLE);
        Set<Strategy> invalid = EnumSet.of(Strategy.STEP_BY_STEP);
        assertThat(StrategySwitcher.next(used, invalid)).contains(Strategy.CONTRAST);
        assertThat(StrategySwitcher.remaining(used, invalid))
                .containsExactly(Strategy.CONTRAST, Strategy.VISUAL_MAP);
    }

    @Test
    @DisplayName("all excluded → empty")
    void next_allExcluded() {
        Set<Strategy> all = EnumSet.allOf(Strategy.class);
        assertThat(StrategySwitcher.next(all, Set.of())).isEmpty();
        assertThat(StrategySwitcher.remaining(all, null)).isEmpty();
    }

    @Test
    @DisplayName("procedural memory line format [STRATEGY][有效/无效]")
    void proceduralLine() {
        assertThat(StrategySwitcher.proceduralMemoryLine(Strategy.ANALOGY, true, null))
                .isEqualTo("[ANALOGY][有效]");
        assertThat(StrategySwitcher.proceduralMemoryLine(Strategy.EXAMPLE, false, "学生仍混淆边界"))
                .isEqualTo("[EXAMPLE][无效] 学生仍混淆边界");
    }

    @Test
    @DisplayName("ORDER is stable and complete")
    void orderComplete() {
        List<Strategy> order = StrategySwitcher.ORDER;
        assertThat(order).hasSize(Strategy.values().length);
        assertThat(EnumSet.copyOf(order)).containsExactlyInAnyOrder(Strategy.values());
    }

    @Test
    @DisplayName("每种策略都有中文名与讲法说明；重讲指令含节点、策略名与『避免重复』")
    void labelsAndDirective() {
        for (Strategy s : Strategy.values()) {
            assertThat(StrategySwitcher.label(s)).isNotBlank();
            assertThat(StrategySwitcher.instruction(s)).isNotBlank();
        }
        assertThat(StrategySwitcher.label(Strategy.ANALOGY)).isEqualTo("类比");
        assertThat(StrategySwitcher.label(Strategy.VISUAL_MAP)).isEqualTo("图谱");
        String directive = StrategySwitcher.reexplainDirective(Strategy.CONTRAST, "用例图（SE-3.2）");
        assertThat(directive)
                .contains("用例图（SE-3.2）")
                .contains("理解探针未通过")
                .contains("『对比』")
                .contains(StrategySwitcher.instruction(Strategy.CONTRAST))
                .contains("避免重复上次的讲法");
    }
}
