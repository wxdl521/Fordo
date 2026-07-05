package com.wenjin.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
}