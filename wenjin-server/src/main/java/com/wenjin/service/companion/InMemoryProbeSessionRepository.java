package com.wenjin.service.companion;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内实现：单测与无数据库场景。同一实例被两个 {@link ProbeSessionStore} 共享即可模拟「重启后数据仍在」。 */
public class InMemoryProbeSessionRepository implements ProbeSessionRepository {

    private final ConcurrentHashMap<String, ProbeSessionStore.ProbeSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void insert(ProbeSessionStore.ProbeSession session) {
        sessions.put(session.sessionId(), session);
    }

    @Override
    public Optional<ProbeSessionStore.ProbeSession> findById(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public boolean markSubmitted(String sessionId, Instant at, boolean correct) {
        boolean[] won = {false};
        sessions.computeIfPresent(sessionId, (id, s) -> {
            if (s.submitted()) {
                return s;
            }
            won[0] = true;
            return new ProbeSessionStore.ProbeSession(s.sessionId(), s.studentId(), s.courseId(), s.nodeCode(),
                    s.nodeId(), s.stem(), s.type(), s.options(), s.correctKey(), s.source(), s.bankQuestionId(),
                    s.createdAt(), true, correct);
        });
        return won[0];
    }

    @Override
    public Set<Long> usedBankQuestionIds(Long studentId, Long courseId, String nodeCode) {
        Set<Long> used = new HashSet<>();
        for (ProbeSessionStore.ProbeSession s : sessions.values()) {
            if (Objects.equals(s.studentId(), studentId) && Objects.equals(s.courseId(), courseId)
                    && Objects.equals(nodeCode, s.nodeCode()) && s.bankQuestionId() != null) {
                used.add(s.bankQuestionId());
            }
        }
        return used;
    }

    @Override
    public int deleteCreatedBefore(Instant cutoff) {
        int before = sessions.size();
        sessions.values().removeIf(s -> s.createdAt() != null && s.createdAt().isBefore(cutoff));
        return before - sessions.size();
    }

    @Override
    public void deleteAll() {
        sessions.clear();
    }

    @Override
    public long count() {
        return sessions.size();
    }
}
