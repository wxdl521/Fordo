package com.wenjin.service;

import org.springframework.stereotype.Component;

/**
 * 掌握度时间衰减计算器（纯函数，无状态）。
 * <p>
 * 采用带地板的指数衰减模型，是对艾宾浩斯遗忘曲线的工程化简化：
 * {@code effective = floor + (raw − floor) × exp(−days / τ)}。
 * 学过的高分不会退回「完全未学」（由 {@code floor} 保底）；随时间推移可衰减部分按指数趋近地板，
 * 符合「遗忘先快后慢」的共识形态。{@code τ} 为时间常数（默认 30 天），约 30 天不碰时
 * 可衰减部分衰减约 63%（{@code 1 − e^{−1}}），适合学期尺度下自然进入复习队列。
 * <p>
 * 约束：{@code raw ≤ floor} 时不衰减（无可遗忘部分）；{@code mastery_level = 0}（未学）不衰减。
 * 衰减仅在读取时计算，不写回数据库。
 */
@Component
public class MasteryDecayCalculator {

    private MasteryDecayCalculator() {
        // 工具类语义：禁止实例化，保留 @Component 以便 Spring 扫描与后续注入扩展
    }

    /**
     * 计算有效掌握度分值（核心纯函数）。
     *
     * @param raw      原始掌握度 {@code student_mastery.mastery_score}
     * @param days     距上次证据的天数（可为小数）
     * @param tauDays  衰减时间常数 τ（天）
     * @param floor    衰减地板分
     * @return 读时有效分；{@code raw ≤ floor} 或 {@code days ≤ 0} 时返回 {@code raw}
     */
    public static double effectiveScore(double raw, double days, double tauDays, double floor) {
        if (raw <= floor || days <= 0) {
            return raw;
        }
        if (tauDays <= 0) {
            return raw;
        }
        double decayFactor = Math.exp(-days / tauDays);
        return floor + (raw - floor) * decayFactor;
    }

    /**
     * 计算有效掌握度分值，并考虑掌握等级豁免。
     *
     * @param masteryLevel {@code student_mastery.mastery_level}；为 0（未学）时不衰减
     */
    public static double effectiveScore(double raw, double days, double tauDays, double floor, int masteryLevel) {
        if (masteryLevel == 0) {
            return raw;
        }
        return effectiveScore(raw, days, tauDays, floor);
    }

    /**
     * 由有效分重算掌握等级，阈值语义与 {@link com.wenjin.service.impl.MasteryServiceImpl#levelOf} 一致。
     *
     * @return 2=已掌握，1=薄弱，0=未学
     */
    public static int effectiveLevel(double effectiveScore, double masteredThreshold, double weakThreshold) {
        if (effectiveScore >= masteredThreshold) {
            return 2;
        }
        if (effectiveScore >= weakThreshold) {
            return 1;
        }
        return 0;
    }
}