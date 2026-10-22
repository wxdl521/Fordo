package com.wenjin.service.code;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * 每学生滑动一小时的执行次数上限（内存态）。
 * <p><b>单实例约束</b>：计数只在本 JVM 内；多实例部署时每台各自计数（实际上限 ≈ 实例数 × 配额），
 * 见 DEPLOYMENT.md「代码执行沙箱」。</p>
 */
final class StudentRateLimiter {

    static final Duration WINDOW = Duration.ofHours(1);
    /** 跟踪的学生数超过此值时顺手清理过期记录 */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final Clock clock;
    private final IntSupplier perHour;
    private final Map<Long, Deque<Instant>> hits = new ConcurrentHashMap<>();

    StudentRateLimiter(Clock clock, IntSupplier perHour) {
        this.clock = clock;
        this.perHour = perHour;
    }

    /** 未超限则记一次并返回 true；上限 ≤ 0 表示不限（不记录）。 */
    boolean tryAcquire(long studentId) {
        int limit = perHour.getAsInt();
        if (limit <= 0) {
            return true;
        }
        if (hits.size() > SWEEP_THRESHOLD) {
            sweep();
        }
        Instant now = clock.instant();
        while (true) {
            Deque<Instant> q = hits.computeIfAbsent(studentId, k -> new ArrayDeque<>());
            synchronized (q) {
                if (hits.get(studentId) != q) {
                    continue; // 刚被 sweep 移除，换新队列重试，避免计数丢失
                }
                prune(q, now);
                if (q.size() >= limit) {
                    return false;
                }
                q.addLast(now);
                return true;
            }
        }
    }

    /** 退回最近一次（请求没有得到执行结果：等不到并发许可，或沙箱基础设施失败）。 */
    void refund(long studentId) {
        Deque<Instant> q = hits.get(studentId);
        if (q == null) {
            return;
        }
        synchronized (q) {
            q.pollLast();
        }
    }

    /** 清掉窗口外的记录与空队列。 */
    void sweep() {
        Instant now = clock.instant();
        hits.forEach((id, q) -> {
            synchronized (q) {
                prune(q, now);
                if (q.isEmpty()) {
                    hits.remove(id, q);
                }
            }
        });
    }

    int trackedStudents() {
        return hits.size();
    }

    private static void prune(Deque<Instant> q, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
            q.pollFirst();
        }
    }
}
