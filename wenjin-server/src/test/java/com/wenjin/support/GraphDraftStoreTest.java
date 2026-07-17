package com.wenjin.support;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.GraphImportRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void saveThenGet_roundTripsWithOwner() {
        GraphDraftStore store = new GraphDraftStore(Clock.systemUTC());
        GraphImportRequest draft = new GraphImportRequest();
        String id = store.save(7L, "C1", draft);
        GraphDraftStore.Entry e = store.get(id, 7L);
        assertEquals("C1", e.courseCode);
        assertEquals(7L, e.teacherId);
        assertSame(draft, e.draft);
        assertTrue(e.estimatedBytes > 0 || e.estimatedBytes == 256L);
    }

    @Test
    void unknownId_throwsNotFound() {
        GraphDraftStore store = new GraphDraftStore(Clock.systemUTC());
        assertThrows(BusinessException.class, () -> store.get("nope", 1L));
    }

    @Test
    void wrongOwner_throwsNotFound() {
        GraphDraftStore store = new GraphDraftStore();
        String id = store.save(1L, "C1", new GraphImportRequest());
        BusinessException ex = assertThrows(BusinessException.class, () -> store.get(id, 2L));
        assertEquals(ResultCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void expiredDraft_throwsNotFound() {
        MovableClock clock = new MovableClock(Instant.parse("2026-06-26T00:00:00Z"));
        GraphDraftStore store = new GraphDraftStore(clock);
        String id = store.save(1L, "C1", new GraphImportRequest());
        clock.advanceMinutes(31); // TTL=30min
        assertThrows(BusinessException.class, () -> store.get(id, 1L));
    }

    @Test
    void capacity_totalFull_returns429NotEvict() {
        LimitsProperties limits = new LimitsProperties();
        limits.getDraft().setMaxTotal(2);
        limits.getDraft().setMaxPerUser(10);
        GraphDraftStore store = new GraphDraftStore(Clock.systemUTC(), limits);
        String a = store.save(1L, "C1", new GraphImportRequest());
        String b = store.save(2L, "C2", new GraphImportRequest());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> store.save(3L, "C3", new GraphImportRequest()));
        assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
        // 有效草稿仍在
        store.get(a, 1L);
        store.get(b, 2L);
    }

    @Test
    void capacity_perUserFull_returns429() {
        LimitsProperties limits = new LimitsProperties();
        limits.getDraft().setMaxTotal(100);
        limits.getDraft().setMaxPerUser(2);
        GraphDraftStore store = new GraphDraftStore(Clock.systemUTC(), limits);
        store.save(1L, "C1", new GraphImportRequest());
        store.save(1L, "C1", new GraphImportRequest());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> store.save(1L, "C1", new GraphImportRequest()));
        assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
    }

    @Test
    void validate_nodesOverLimit_413() {
        LimitsProperties limits = new LimitsProperties();
        limits.getDraft().setMaxNodes(2);
        GraphDraftStore store = new GraphDraftStore(Clock.systemUTC(), limits);
        GraphImportRequest draft = new GraphImportRequest();
        draft.setNodes(new ArrayList<>());
        for (int i = 0; i < 3; i++) {
            GraphImportRequest.NodeItem n = new GraphImportRequest.NodeItem();
            n.setId("N" + i);
            n.setName("node" + i);
            draft.getNodes().add(n);
        }
        BusinessException ex = assertThrows(BusinessException.class,
                () -> store.save(1L, "C1", draft));
        assertEquals(ResultCode.PAYLOAD_TOO_LARGE.getCode(), ex.getCode());
    }

    @Test
    void purge_removesExpiredBeforeCapacityCheck() {
        MovableClock clock = new MovableClock(Instant.parse("2026-06-26T00:00:00Z"));
        LimitsProperties limits = new LimitsProperties();
        limits.getDraft().setMaxTotal(1);
        limits.getDraft().setTtlMinutes(30);
        GraphDraftStore store = new GraphDraftStore(clock, limits);
        store.save(1L, "C1", new GraphImportRequest());
        clock.advanceMinutes(31);
        // 过期草稿被 purge 后应能再存
        String id = store.save(2L, "C2", new GraphImportRequest());
        store.get(id, 2L);
    }

    @Test
    void remove_requiresOwner() {
        GraphDraftStore store = new GraphDraftStore();
        String id = store.save(1L, "C1", new GraphImportRequest());
        BusinessException ex = assertThrows(BusinessException.class, () -> store.remove(id, 9L));
        assertEquals(ResultCode.NOT_FOUND.getCode(), ex.getCode());
        store.remove(id, 1L);
        assertThrows(BusinessException.class, () -> store.get(id, 1L));
    }
}
