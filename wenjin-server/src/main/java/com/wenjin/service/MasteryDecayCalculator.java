package com.wenjin.service;

import com.wenjin.entity.StudentMastery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 掌握度时间衰减计算器（可注入单例，线程安全）。
 * <p>
 * 采用带地板的指数衰减模型，是对艾宾浩斯遗忘曲线的工程化简化：
 * {@code effective = floor + (raw − floor) × exp(−days / τ)}。
 * 学过的高分不会退回「完全未学」（由 {@code floor} 保底）；随时间推移可衰减部分按指数趋近地板，
 * 符合「遗忘先快后慢」的共识形态。{@code τ} 为时间常数（默认 30 天），约 30 天不碰时
 * 可衰减部分衰减约 63%（{@code 1 − e^{−1}}），适合学期尺度下自然进入复习队列。
 * <p>
 * 约束：{@code raw ≤ floor} 时不衰减（无可遗忘部分）；{@code mastery_level = 0}（未学）不衰减。
 * 衰减仅在读取时计算，不写回数据库。
 * <p>
 * <b>保险丝语义（{@code wenjin.mastery.decay.enabled=false}）</b>：
 * {@code enabled=false} 回退的是「衰减」（effective≡raw）；等级口径仍由分数阈值重算，
 * 不回读 DB 的 {@code mastery_level}——若历史行的 {@code mastery_level} 与分数阈值不一致，
 * 关闭开关也不会复现按 DB 等级染色的旧行为，这是有意取舍。
 */
@Component
public class MasteryDecayCalculator {

    /** 衰减总开关；false 时 effective≡raw，其余参数忽略。 */
    @Value("${wenjin.mastery.decay.enabled:true}")
    private boolean enabled;

    /** 衰减时间常数 τ（天）。 */
    @Value("${wenjin.mastery.decay.tau-days:30}")
    private double tauDays;

    /** 衰减地板分：有效分不会低于此值。 */
    @Value("${wenjin.mastery.decay.floor:25}")
    private double floor;

    /** 已掌握分数阈值（≥ 此值视为已掌握）。 */
    @Value("${wenjin.mastery.mastered-threshold:75}")
    private double masteredThreshold;

    /** 薄弱分数阈值（≥ 此值且 < masteredThreshold 视为薄弱）。 */
    @Value("${wenjin.mastery.weak-threshold:40}")
    private double weakThreshold;

    // ─────────────────── 静态纯函数（保持原签名不变）───────────────────

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

    // ─────────────────── 实例方法（委托静态纯函数，携带配置）───────────────────

    /**
     * 读时有效分。
     * <p>
     * 前置条件：{@code m.getMasteryScore() != null}（调用方负责过滤，违反时抛 NPE）。
     * {@code enabled=false} 时直接返回 raw；否则委托静态 {@link #effectiveScore(double, double, double, double, int)}，
     * {@code mastery_level} 为 null 时视为 0（未学，不衰减）。
     */
    public double effectiveScoreOf(StudentMastery m) {
        double raw = m.getMasteryScore().doubleValue();
        if (!enabled) {
            return raw;
        }
        int level = m.getMasteryLevel() != null ? m.getMasteryLevel() : 0;
        return effectiveScore(raw, daysSinceEvidence(m.getUpdatedAt()), tauDays, floor, level);
    }

    /**
     * 读时有效分（重载，接受调用方已算好的 {@code days}，跳过内部 {@code LocalDateTime.now()} 重算）。
     * <p>
     * 语义与 {@link #effectiveScoreOf(StudentMastery)} 完全相同：
     * {@code enabled=false} 仍先返回 raw；{@code mastery_level} 为 null 时视为 0（不衰减）。
     * 供调用方已持有 {@code days} 变量（如 Graph/Review）时使用，避免两次 {@code LocalDateTime.now()} 偏差。
     *
     * @param m    掌握行（前置条件：{@code m.getMasteryScore() != null}）
     * @param days 距上次证据的天数（由调用方提前用 {@link #daysSinceEvidence} 算好）
     */
    public double effectiveScoreOf(StudentMastery m, double days) {
        double raw = m.getMasteryScore().doubleValue();
        if (!enabled) {
            return raw;
        }
        int level = m.getMasteryLevel() != null ? m.getMasteryLevel() : 0;
        return effectiveScore(raw, days, tauDays, floor, level);
    }

    /**
     * 由有效分重算掌握等级，委托静态 {@link #effectiveLevel(double, double, double)}，
     * 阈值取实例配置的 {@code masteredThreshold} / {@code weakThreshold}。
     *
     * @return 2=已掌握，1=薄弱，0=未学
     */
    public int effectiveLevelOf(double effectiveScore) {
        return effectiveLevel(effectiveScore, masteredThreshold, weakThreshold);
    }

    /**
     * 距 {@code updated_at} 的天数（可为小数）。
     * {@code updatedAt} 为 null 时返回 0.0（视为无时间流逝，不衰减）。
     */
    public double daysSinceEvidence(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            return 0.0;
        }
        return Duration.between(updatedAt, LocalDateTime.now()).toMillis() / (24.0 * 60 * 60 * 1000);
    }

    /**
     * 已掌握分数阈值，供复习入队等需要外部对齐阈值的判据使用。
     */
    public double getMasteredThreshold() {
        return masteredThreshold;
    }
}
