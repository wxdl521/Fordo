package com.wenjin.service.memory;

import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.StudentMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MemoryStrengthCalculatorTest {

    private static final double LAMBDA = 0.05;

    @Test
    @DisplayName("days=0 → effective = salience")
    void zeroDays_returnsSalience() {
        assertThat(MemoryStrengthCalculator.effective(1.0, 0.0, LAMBDA)).isEqualTo(1.0);
        assertThat(MemoryStrengthCalculator.effective(0.8, -1.0, LAMBDA)).isEqualTo(0.8);
    }

    @Test
    @DisplayName("effective = salience * exp(-λ * days)")
    void formulaMatches() {
        double days = 10.0;
        double expected = 1.0 * Math.exp(-LAMBDA * days);
        assertThat(MemoryStrengthCalculator.effective(1.0, days, LAMBDA))
                .isCloseTo(expected, within(1e-9));
    }

    @Test
    @DisplayName("Clock 可推进：强化后隔日 effective 衰减")
    void clockAdvances_decays() {
        Instant t0 = Instant.parse("2026-07-01T00:00:00Z");
        MovableClock clock = new MovableClock(t0);
        LimitsProperties limits = new LimitsProperties();
        limits.getMemory().setLambda(LAMBDA);
        MemoryStrengthCalculator calc = new MemoryStrengthCalculator(clock, limits);

        StudentMemory mem = new StudentMemory();
        mem.setSalience(BigDecimal.ONE);
        mem.setLastReinforcedAt(LocalDateTime.ofInstant(t0, ZoneOffset.UTC));

        assertThat(calc.effectiveOf(mem)).isCloseTo(1.0, within(1e-9));

        clock.advance(Duration.ofDays(20));
        double expected = Math.exp(-LAMBDA * 20.0);
        assertThat(calc.effectiveOf(mem)).isCloseTo(expected, within(1e-6));
    }

    @Test
    @DisplayName("lambda≤0 时不衰减")
    void nonPositiveLambda_noDecay() {
        assertThat(MemoryStrengthCalculator.effective(0.9, 100.0, 0.0)).isEqualTo(0.9);
        assertThat(MemoryStrengthCalculator.effective(0.9, 100.0, -0.1)).isEqualTo(0.9);
    }

    static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.truncatedTo(ChronoUnit.MILLIS);
        }
    }
}
