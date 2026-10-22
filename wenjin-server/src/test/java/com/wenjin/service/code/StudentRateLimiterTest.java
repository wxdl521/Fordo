package com.wenjin.service.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StudentRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T08:00:00Z"));
    private final AtomicInteger perHour = new AtomicInteger(3);
    private final StudentRateLimiter limiter = new StudentRateLimiter(clock, perHour::get);

    @Test
    @DisplayName("每学生滑动一小时：满额后拒绝，最早一次满 1 小时后放出一个名额")
    void slidingWindow() {
        assertThat(limiter.tryAcquire(1L)).isTrue();
        clock.advance(Duration.ofMinutes(10));
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();

        // 其他学生不受影响
        assertThat(limiter.tryAcquire(2L)).isTrue();

        clock.advance(Duration.ofMinutes(49)); // 距第一次 59 分钟
        assertThat(limiter.tryAcquire(1L)).isFalse();
        clock.advance(Duration.ofMinutes(1));  // 距第一次整 1 小时：窗口外
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();
    }

    @Test
    @DisplayName("refund 归还最近一次名额（并发许可拿不到时不计次）")
    void refund() {
        perHour.set(1);
        assertThat(limiter.tryAcquire(7L)).isTrue();
        assertThat(limiter.tryAcquire(7L)).isFalse();
        limiter.refund(7L);
        assertThat(limiter.tryAcquire(7L)).isTrue();
        limiter.refund(99L); // 未知学生：无副作用
    }

    @Test
    @DisplayName("上限现读：调小立即生效；≤0 表示不限")
    void limitReadEachTime() {
        perHour.set(0);
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire(3L)).isTrue();
        }
        perHour.set(2);
        assertThat(limiter.tryAcquire(4L)).isTrue();
        assertThat(limiter.tryAcquire(4L)).isTrue();
        assertThat(limiter.tryAcquire(4L)).isFalse();
    }

    @Test
    @DisplayName("过期记录被清理：长时间不活跃的学生不再占内存")
    void sweep() {
        for (long s = 1; s <= 50; s++) {
            limiter.tryAcquire(s);
        }
        assertThat(limiter.trackedStudents()).isEqualTo(50);
        clock.advance(Duration.ofHours(2));
        limiter.sweep();
        assertThat(limiter.trackedStudents()).isZero();
    }
}
