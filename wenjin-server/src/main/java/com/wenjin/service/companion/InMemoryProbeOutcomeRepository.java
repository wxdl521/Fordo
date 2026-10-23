package com.wenjin.service.companion;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内实现：单测与无数据库场景；共享同一实例即可模拟「重启后数据仍在」。 */
public class InMemoryProbeOutcomeRepository implements ProbeOutcomeRepository {

    private record Key(Long studentId, Long courseId, String nodeCode) {
    }

    private final ConcurrentHashMap<Key, ProbeOutcomeStore.NodeOutcome> rows = new ConcurrentHashMap<>();

    @Override
    public Optional<ProbeOutcomeStore.NodeOutcome> find(Long studentId, Long courseId, String nodeCode) {
        return Optional.ofNullable(rows.get(new Key(studentId, courseId, nodeCode)));
    }

    @Override
    public Optional<ProbeOutcomeStore.NodeOutcome> findMostRecent(Long studentId, Long courseId) {
        return rows.entrySet().stream()
                .filter(e -> studentId.equals(e.getKey().studentId()) && courseId.equals(e.getKey().courseId()))
                .map(Map.Entry::getValue)
                .max(Comparator.comparing(ProbeOutcomeStore.NodeOutcome::at));
    }

    @Override
    public void save(Long studentId, Long courseId, ProbeOutcomeStore.NodeOutcome outcome) {
        rows.put(new Key(studentId, courseId, outcome.nodeCode()), outcome);
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        int before = rows.size();
        rows.values().removeIf(o -> o.at() == null || o.at().isBefore(cutoff));
        return before - rows.size();
    }

    @Override
    public void deleteAll() {
        rows.clear();
    }

    @Override
    public long count() {
        return rows.size();
    }
}
