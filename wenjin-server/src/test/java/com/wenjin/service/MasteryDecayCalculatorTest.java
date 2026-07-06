package com.wenjin.service;

import com.wenjin.entity.StudentMastery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link MasteryDecayCalculator} 性质与边界测试：单调递减、零日无衰减、渐近地板、
 * raw≤floor / level=0 豁免、τ/floor 参数化、极大 days 数值稳定。
 */
class MasteryDecayCalculatorTest {

    private static final double TAU_DAYS = 30.0;
    private static final double FLOOR = 25.0;
    private static final double MASTERED = 75.0;
    private static final double WEAK = 40.0;

    @Test
    @DisplayName("days=0 时有效分等于 raw（无时间流逝）")
    void daysZero_returnsRaw() {
        assertThat(MasteryDecayCalculator.effectiveScore(88.0, 0.0, TAU_DAYS, FLOOR))
                .isEqualTo(88.0);
        assertThat(MasteryDecayCalculator.effectiveScore(50.0, 0.0, TAU_DAYS, FLOOR))
                .isEqualTo(50.0);
    }

    @Test
    @DisplayName("性质：raw>floor 时随 days 增大单调递减")
    void monotonicDecrease_asDaysIncrease() {
        double raw = 88.0;
        double prev = MasteryDecayCalculator.effectiveScore(raw, 0.0, TAU_DAYS, FLOOR);
        for (double days : new double[]{5, 10, 20, 30, 40, 60, 90}) {
            double current = MasteryDecayCalculator.effectiveScore(raw, days, TAU_DAYS, FLOOR);
            assertThat(current).isLessThan(prev);
            assertThat(current).isGreaterThan(FLOOR);
            prev = current;
        }
    }

    @Test
    @DisplayName("性质：days 极大时渐近 floor")
    void asymptoticFloor_largeDays() {
        double raw = 95.0;
        double effective = MasteryDecayCalculator.effectiveScore(raw, 10_000.0, TAU_DAYS, FLOOR);
        assertThat(effective).isCloseTo(FLOOR, within(0.01));
    }

    @Test
    @DisplayName("raw≤floor 时不衰减（本来就低，无可遗忘部分）")
    void rawAtOrBelowFloor_unchanged() {
        assertThat(MasteryDecayCalculator.effectiveScore(25.0, 100.0, TAU_DAYS, FLOOR))
                .isEqualTo(25.0);
        assertThat(MasteryDecayCalculator.effectiveScore(20.0, 100.0, TAU_DAYS, FLOOR))
                .isEqualTo(20.0);
        assertThat(MasteryDecayCalculator.effectiveScore(10.0, 365.0, TAU_DAYS, FLOOR))
                .isEqualTo(10.0);
    }

    @Test
    @DisplayName("mastery_level=0（未学）不衰减")
    void masteryLevelZero_noDecay() {
        assertThat(MasteryDecayCalculator.effectiveScore(0.0, 100.0, TAU_DAYS, FLOOR, 0))
                .isEqualTo(0.0);
        assertThat(MasteryDecayCalculator.effectiveScore(65.0, 60.0, TAU_DAYS, FLOOR, 0))
                .isEqualTo(65.0);
    }

    @ParameterizedTest(name = "raw={0} days={1} tau={2} floor={3} → {4}")
    @CsvSource({
            "88,  0,  30, 25, 88",
            "88, 30,  30, 25, 48.17",
            "100, 30, 15, 20, 30.83",
            "60,  10,  20, 30, 48.20"
    })
    @DisplayName("τ/floor 参数化：手算对照")
    void parameterizedTauAndFloor(double raw, double days, double tau, double floor, double expected) {
        assertThat(MasteryDecayCalculator.effectiveScore(raw, days, tau, floor))
                .isCloseTo(expected, within(0.1));
    }

    @Test
    @DisplayName("数值稳定：days 极大不产生 NaN 或 Infinity")
    void extremeDays_noNaN() {
        double[] extremeDays = {1e6, 1e9, Double.MAX_VALUE / 2};
        for (double days : extremeDays) {
            double result = MasteryDecayCalculator.effectiveScore(88.0, days, TAU_DAYS, FLOOR);
            assertThat(result).isFinite();
            assertThat(result).isGreaterThanOrEqualTo(FLOOR);
            assertThat(result).isLessThanOrEqualTo(88.0);
        }
    }

    @Test
    @DisplayName("文档示例：88 分 40 天后 effective≈47，等级由已掌握降为薄弱")
    void docExample_88After40Days_downgradesLevel() {
        double effective = MasteryDecayCalculator.effectiveScore(88.0, 40.0, TAU_DAYS, FLOOR);
        assertThat(effective).isCloseTo(41.6, within(1.0));

        int level = MasteryDecayCalculator.effectiveLevel(effective, MASTERED, WEAK);
        assertThat(level).isEqualTo(1);
    }

    @Test
    @DisplayName("effectiveLevel 阈值与 MasteryServiceImpl.levelOf 一致：75/40")
    void effectiveLevel_matchesThresholds() {
        assertThat(MasteryDecayCalculator.effectiveLevel(75.0, MASTERED, WEAK)).isEqualTo(2);
        assertThat(MasteryDecayCalculator.effectiveLevel(88.0, MASTERED, WEAK)).isEqualTo(2);
        assertThat(MasteryDecayCalculator.effectiveLevel(74.9, MASTERED, WEAK)).isEqualTo(1);
        assertThat(MasteryDecayCalculator.effectiveLevel(40.0, MASTERED, WEAK)).isEqualTo(1);
        assertThat(MasteryDecayCalculator.effectiveLevel(39.9, MASTERED, WEAK)).isEqualTo(0);
        assertThat(MasteryDecayCalculator.effectiveLevel(0.0, MASTERED, WEAK)).isEqualTo(0);
    }

    @Test
    @DisplayName("五参重载：level≠0 时委托四参核心公式")
    void fiveArgOverload_delegatesWhenLevelNonZero() {
        double fourArg = MasteryDecayCalculator.effectiveScore(80.0, 15.0, TAU_DAYS, FLOOR);
        double fiveArg = MasteryDecayCalculator.effectiveScore(80.0, 15.0, TAU_DAYS, FLOOR, 2);
        assertThat(fiveArg).isEqualTo(fourArg);
    }

    // ─────────────────── 实例方法测试 ───────────────────

    /** 小工厂：构建配置好的 MasteryDecayCalculator 实例。 */
    private MasteryDecayCalculator calculator(boolean enabled) {
        MasteryDecayCalculator calc = new MasteryDecayCalculator();
        ReflectionTestUtils.setField(calc, "enabled", enabled);
        ReflectionTestUtils.setField(calc, "tauDays", TAU_DAYS);
        ReflectionTestUtils.setField(calc, "floor", FLOOR);
        ReflectionTestUtils.setField(calc, "masteredThreshold", MASTERED);
        ReflectionTestUtils.setField(calc, "weakThreshold", WEAK);
        return calc;
    }

    private StudentMastery mastery(double score, int level, LocalDateTime updatedAt) {
        StudentMastery m = new StudentMastery();
        m.setMasteryScore(new BigDecimal(String.valueOf(score)));
        m.setMasteryLevel(level);
        m.setUpdatedAt(updatedAt);
        return m;
    }

    @Test
    @DisplayName("实例：保险丝关闭（enabled=false）→ effectiveScoreOf 直接返回 raw")
    void instance_fuseOff_returnsRaw() {
        MasteryDecayCalculator calc = calculator(false);
        StudentMastery m = mastery(88.0, 2, LocalDateTime.now().minusDays(40));
        // 即便有 40 天的衰减证据，关闭开关后 effective≡raw
        assertThat(calc.effectiveScoreOf(m)).isEqualTo(88.0);
    }

    @Test
    @DisplayName("实例：updatedAt=null → daysSinceEvidence=0 → 不衰减，effective=raw")
    void instance_updatedAtNull_daysZero_noDecay() {
        MasteryDecayCalculator calc = calculator(true);
        assertThat(calc.daysSinceEvidence(null)).isEqualTo(0.0);
        StudentMastery m = mastery(88.0, 2, null);
        assertThat(calc.effectiveScoreOf(m)).isEqualTo(88.0);
    }

    @Test
    @DisplayName("实例：mastery_level=0（未学）→ effectiveScoreOf 不衰减，返回 raw")
    void instance_levelZero_noDecay() {
        MasteryDecayCalculator calc = calculator(true);
        StudentMastery m = mastery(65.0, 0, LocalDateTime.now().minusDays(60));
        // level=0 豁免衰减，即使有陈旧证据
        assertThat(calc.effectiveScoreOf(m)).isEqualTo(65.0);
    }

    @Test
    @DisplayName("实例：effectiveLevelOf 阈值边界 — =75→2，=40→1，<40→0")
    void instance_effectiveLevelOf_boundaries() {
        MasteryDecayCalculator calc = calculator(true);
        assertThat(calc.effectiveLevelOf(75.0)).isEqualTo(2);   // 恰好等于已掌握阈值
        assertThat(calc.effectiveLevelOf(76.0)).isEqualTo(2);
        assertThat(calc.effectiveLevelOf(74.9)).isEqualTo(1);
        assertThat(calc.effectiveLevelOf(40.0)).isEqualTo(1);   // 恰好等于薄弱阈值
        assertThat(calc.effectiveLevelOf(39.9)).isEqualTo(0);
        assertThat(calc.effectiveLevelOf(0.0)).isEqualTo(0);
    }

    @Test
    @DisplayName("实例：getMasteredThreshold 返回配置值 75")
    void instance_getMasteredThreshold() {
        MasteryDecayCalculator calc = calculator(true);
        assertThat(calc.getMasteredThreshold()).isEqualTo(MASTERED);
    }

    @Test
    @DisplayName("实例：保险丝开启时 effectiveScoreOf 与手算静态方法一致")
    void instance_fuseOn_matchesStaticMethod() {
        MasteryDecayCalculator calc = calculator(true);
        LocalDateTime stale = LocalDateTime.now().minusDays(40);
        StudentMastery m = mastery(88.0, 2, stale);
        double expected = MasteryDecayCalculator.effectiveScore(88.0, 40.0, TAU_DAYS, FLOOR, 2);
        assertThat(calc.effectiveScoreOf(m)).isCloseTo(expected, within(0.5));
    }

    // ─────────────────── 带 days 重载（T2-m1）───────────────────

    @Test
    @DisplayName("重载 effectiveScoreOf(m, days)：保险丝开启时与单参版在同 days 下结果一致")
    void overload_fuseOn_sameResultAsSingleArg() {
        MasteryDecayCalculator calc = calculator(true);
        // 固定 days=40 传入重载，使两次调用的 days 完全相同，避免时间偏差
        StudentMastery m = mastery(88.0, 2, LocalDateTime.now().minusDays(40));
        double singleArg = calc.effectiveScoreOf(m);
        double withDays = calc.effectiveScoreOf(m, 40.0);
        assertThat(withDays).isCloseTo(singleArg, within(0.5));
    }

    @Test
    @DisplayName("重载 effectiveScoreOf(m, days)：保险丝关闭时返回 raw（忽略 days 参数）")
    void overload_fuseOff_returnsRaw() {
        MasteryDecayCalculator calc = calculator(false);
        StudentMastery m = mastery(88.0, 2, LocalDateTime.now().minusDays(40));
        assertThat(calc.effectiveScoreOf(m, 40.0)).isEqualTo(88.0);
    }
}