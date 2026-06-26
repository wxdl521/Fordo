package com.wenjin.support;

import com.wenjin.common.BusinessException;
import com.wenjin.dto.GraphImportRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphDraftStoreTest {

    /** 可推进的测试时钟。 */
    static class MovableClock extends Clock {
        private Instant now;
        MovableClock(Instant start) { this.now = start; }
        void advanceMinutes(long m) { now = now.plusSeconds(m * 60); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @Test
    void saveThenGet_roundTrips() {
        GraphDraftStore store = new GraphDraftStore(Clock.systemUTC());
        GraphImportRequest draft = new GraphImportRequest();
        String id = store.save("C1", draft);
        GraphDraftStore.Entry e = store.get(id);
        assertEquals("C1", e.courseCode);
        assertSame(draft, e.draft);
    }

    @Test
    void unknownId_throwsNotFound() {
        GraphDraftStore store = new GraphDraftStore(Clock.systemUTC());
        assertThrows(BusinessException.class, () -> store.get("nope"));
    }

    @Test
    void expiredDraft_throwsNotFound() {
        MovableClock clock = new MovableClock(Instant.parse("2026-06-26T00:00:00Z"));
        GraphDraftStore store = new GraphDraftStore(clock);
        String id = store.save("C1", new GraphImportRequest());
        clock.advanceMinutes(31); // TTL=30min
        assertThrows(BusinessException.class, () -> store.get(id));
    }
}
