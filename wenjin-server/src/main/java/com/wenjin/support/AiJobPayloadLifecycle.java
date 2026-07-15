package com.wenjin.support;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** Coordinates local payload submissions with cleanup in the supported single-process topology. */
@Component
public final class AiJobPayloadLifecycle {

    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);

    public <T> T withSubmission(Supplier<T> submission) {
        Lock lock = lifecycleLock.readLock();
        lock.lock();
        try {
            return submission.get();
        } finally {
            lock.unlock();
        }
    }

    public void withCleanup(Runnable cleanup) {
        Lock lock = lifecycleLock.writeLock();
        lock.lock();
        try {
            cleanup.run();
        } finally {
            lock.unlock();
        }
    }
}
