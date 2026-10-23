package com.wenjin.service.companion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 可推进的测试时钟（探针存储 TTL / 窗口测试用）。 */
final class ProbeTestClock extends Clock {

    private volatile Instant now;

    ProbeTestClock(Instant start) {
        this.now = start;
    }

    void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
