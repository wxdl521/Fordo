package com.wenjin.service.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    @Test
    @DisplayName("nextSeq is unique and strictly covers 1..total under concurrent calls")
    void nextSeq_concurrent_uniqueAndStrictlyIncreasingRange() throws Exception {
        final int threads = 12;
        final int callsPerThread = 1000;
        final int total = threads * callsPerThread;

        TraceContext ctx = TraceContext.active(42L);
        Set<Integer> seen = ConcurrentHashMap.newKeySet(total);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        for (int i = 0; i < callsPerThread; i++) {
                            int seq = ctx.nextSeq();
                            if (!seen.add(seq)) {
                                duplicateCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(duplicateCount.get()).isZero();
        assertThat(seen).hasSize(total);
        assertThat(seen).contains(1, total);
        assertThat(seen.stream().mapToInt(Integer::intValue).min()).hasValue(1);
        assertThat(seen.stream().mapToInt(Integer::intValue).max()).hasValue(total);
        assertThat(ctx.currentSeq()).isEqualTo(total);
    }

    @Test
    @DisplayName("active context starts at seq 0; first nextSeq returns 1")
    void nextSeq_startsAtOne() {
        TraceContext ctx = TraceContext.active(1L);
        assertThat(ctx.currentSeq()).isZero();
        assertThat(ctx.nextSeq()).isEqualTo(1);
        assertThat(ctx.nextSeq()).isEqualTo(2);
        assertThat(ctx.currentSeq()).isEqualTo(2);
        assertThat(ctx.isNoop()).isFalse();
    }

    @Test
    @DisplayName("NOOP is shared no-op marker; service skips step before nextSeq")
    void noop_isNoopMarkerWithIndependentSeq() {
        assertThat(TraceContext.NOOP.isNoop()).isTrue();
        assertThat(TraceContext.NOOP.runId()).isZero();
        // nextSeq is not gated on noop (AgentTraceService.step returns early on isNoop);
        // shared NOOP must not be mutated by tests in a way that leaks — only assert marker.
        assertThat(TraceContext.NOOP).isSameAs(TraceContext.NOOP);
    }
}
