package com.wenjin.service.memory;

import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.StudentMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * 记忆读时有效强度：{@code effective = salience × exp(−λ · Δdays)}。
 * <p>{@link Clock} 可注入，便于单测推进时间；λ 取自 {@code wenjin.limits.memory.lambda}（默认 0.05）。
 */
@Component
public class MemoryStrengthCalculator {

    private final Clock clock;
    private final LimitsProperties limits;

    @Autowired
    public MemoryStrengthCalculator(LimitsProperties limits) {
        this(Clock.systemUTC(), limits);
    }

    public MemoryStrengthCalculator(Clock clock, LimitsProperties limits) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.limits = limits == null ? new LimitsProperties() : limits;
    }

    /**
     * 纯函数：给定 salience、距上次强化天数与 λ 计算 effective。
     * {@code days ≤ 0} 或 {@code lambda ≤ 0} 时返回 salience（无衰减）。
     */
    public static double effective(double salience, double days, double lambda) {
        if (days <= 0 || lambda <= 0 || Double.isNaN(salience)) {
            return salience;
        }
        return salience * Math.exp(-lambda * days);
    }

    /** 使用配置 λ 与当前 clock 计算实体有效强度。 */
    public double effectiveOf(StudentMemory memory) {
        if (memory == null) {
            return 0.0;
        }
        double salience = salienceOf(memory.getSalience());
        double days = daysSinceReinforced(memory.getLastReinforcedAt());
        return effective(salience, days, lambda());
    }

    /**
     * 距 {@code lastReinforcedAt} 的天数（可为小数）。
     * null 时返回 0（视为刚强化）。
     */
    public double daysSinceReinforced(LocalDateTime lastReinforcedAt) {
        if (lastReinforcedAt == null) {
            return 0.0;
        }
        Instant reinforced = lastReinforcedAt.toInstant(ZoneOffset.UTC);
        Instant now = clock.instant();
        long millis = Duration.between(reinforced, now).toMillis();
        if (millis <= 0) {
            return 0.0;
        }
        return millis / (24.0 * 60 * 60 * 1000);
    }

    /** 当前时刻（与 clock 对齐，写入 last_reinforced_at 时使用）。 */
    public LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public double lambda() {
        double lambda = limits.getMemory().getLambda();
        return lambda < 0 ? 0.0 : lambda;
    }

    private static double salienceOf(BigDecimal salience) {
        if (salience == null) {
            return 0.0;
        }
        return salience.doubleValue();
    }

    /** 测试/装配用：暴露 clock。 */
    Clock clock() {
        return Objects.requireNonNull(clock);
    }
}
