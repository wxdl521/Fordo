package com.wenjin.support;

import com.wenjin.support.DemoDataSeeder.Persona;
import com.wenjin.support.DemoDataSeeder.QMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DemoDataSeeder 作答决策逻辑单测（纯函数，不触发数据库/Spring）。
 * 校验三个人设的差异化作答与"错误指向 Y"的干扰项选择。
 */
class DemoDataSeederTest {

    /** 不依赖任何 mapper/service 的纯逻辑测试，构造时全部传 null。 */
    private DemoDataSeeder seeder() {
        return new DemoDataSeeder(null, null, null, null, null, null, null, null, null);
    }

    // 题面：Q1 主点 KT10，干扰 B/C 均指向 KT05；Q2 主点即 KT05；Q3 主点 KT11、最难、无映射
    private static final QMeta Q1 = new QMeta(1L, "A", 2, "KT10",
            List.<String[]>of(new String[]{"B", "KT05"}, new String[]{"C", "KT05"}));
    private static final QMeta Q2 = new QMeta(2L, "A", 3, "KT05",
            List.<String[]>of(new String[]{"B", "KT05"}));
    private static final QMeta Q3 = new QMeta(3L, "A", 5, "KT11",
            List.<String[]>of(new String[]{"B", null}));

    private Map<Long, QMeta> metaMap() {
        Map<Long, QMeta> m = new LinkedHashMap<>();
        m.put(1L, Q1);
        m.put(2L, Q2);
        m.put(3L, Q3);
        return m;
    }

    @Test
    @DisplayName("mostMappedPrereq：被最多干扰项指向的前置点 = KT05")
    void mostMappedPrereqPicksKT05() {
        String y = seeder().mostMappedPrereq(metaMap(), List.of(1L, 2L, 3L));
        assertThat(y).isEqualTo("KT05");
    }

    @Test
    @DisplayName("B 中间卡顿：对指向 Y 的题选中映射到 Y 的干扰项（错误多指向 Y）")
    void bStuckPicksDistractorMappedToY() {
        // Q1 含指向 KT05 的干扰项 → 应选中错误项 B（映射 KT05），而非正确项 A
        String chosen = seeder().chooseAnswer(Persona.B_STUCK, 1, 0, Q1, "KT05", 3L);
        assertThat(chosen).isEqualTo("B");
        assertThat(chosen).isNotEqualTo(Q1.correctKey());
    }

    @Test
    @DisplayName("B 中间卡顿：与 Y 无关的题（复测）答对")
    void bStuckAnswersUnrelatedCorrectInRound2() {
        String chosen = seeder().chooseAnswer(Persona.B_STUCK, 1, 2, Q3, "KT05", 3L);
        assertThat(chosen).isEqualTo("A"); // 正确项
    }

    @Test
    @DisplayName("C 接近掌握：复测仅最难题答错，其余答对")
    void cNearOnlyHardestWrongInRound2() {
        // Q3 为最难题 → 错
        assertThat(seeder().chooseAnswer(Persona.C_NEAR, 1, 2, Q3, "KT05", 3L)).isNotEqualTo("A");
        // Q1 非最难 → 对
        assertThat(seeder().chooseAnswer(Persona.C_NEAR, 1, 0, Q1, "KT05", 3L)).isEqualTo("A");
    }

    @Test
    @DisplayName("A 基础薄弱：初测大量答错（index 不被 5 整除即错）")
    void aWeakMostlyWrongInRound0() {
        assertThat(seeder().chooseAnswer(Persona.A_WEAK, 0, 0, Q1, "KT05", 3L)).isEqualTo("A"); // index0 对
        assertThat(seeder().chooseAnswer(Persona.A_WEAK, 0, 1, Q2, "KT05", 3L)).isNotEqualTo("A"); // index1 错
    }
}
