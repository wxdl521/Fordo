package com.wenjin.eval.fixture;

import java.util.concurrent.atomic.AtomicInteger;

/** No-op / counting session port for unit tests. */
public final class InMemoryEvalSessionPort implements EvalSessionPort {

    private final AtomicInteger clearCount = new AtomicInteger();

    @Override
    public void clearSessions(long studentId, long courseId) {
        clearCount.incrementAndGet();
    }

    public int clearCount() {
        return clearCount.get();
    }
}
