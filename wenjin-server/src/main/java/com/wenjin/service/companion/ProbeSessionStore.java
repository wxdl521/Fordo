package com.wenjin.service.companion;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for companion {@code probe_understanding} sessions (M9).
 *
 * <p>Probe sessions are deliberately <b>not</b> persisted in {@code practice_session}
 * (avoids V14 conflict with M10 plan tables and keeps EWMA isolation simple).
 * Ownership is enforced on submit via studentId/courseId match.
 *
 * <p>Process-local: lost on restart; sufficient for competition-delivery core.
 */
@Component
public class ProbeSessionStore {

    public enum Source {
        /** Picked from approved bank; not EPHEMERAL. */
        BANK,
        /** LLM / template ephemeral; never written to question bank. */
        EPHEMERAL
    }

    public record ProbeOption(String key, String text) {}

    /**
     * @param correctKey option key for objective items; free-text expected for ephemeral short-answer
     * @param bankQuestionId non-null only when {@link Source#BANK}
     */
    public record ProbeSession(
            String sessionId,
            Long studentId,
            Long courseId,
            String nodeCode,
            Long nodeId,
            String stem,
            Integer type,
            List<ProbeOption> options,
            String correctKey,
            Source source,
            Long bankQuestionId,
            Instant createdAt,
            boolean submitted,
            Boolean correct
    ) {
        public ProbeSession {
            options = options == null ? List.of() : List.copyOf(options);
        }

        public boolean isEphemeral() {
            return source == Source.EPHEMERAL;
        }
    }

    private final ConcurrentHashMap<String, ProbeSession> sessions = new ConcurrentHashMap<>();

    /**
     * Create a new probe session. Returns the stored session (with generated id).
     */
    public ProbeSession create(Long studentId,
                               Long courseId,
                               String nodeCode,
                               Long nodeId,
                               String stem,
                               Integer type,
                               List<ProbeOption> options,
                               String correctKey,
                               Source source,
                               Long bankQuestionId) {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(courseId, "courseId");
        if (!StringUtils.hasText(nodeCode)) {
            throw new IllegalArgumentException("nodeCode is required");
        }
        if (!StringUtils.hasText(stem)) {
            throw new IllegalArgumentException("stem is required");
        }
        Source src = source == null ? Source.EPHEMERAL : source;
        if (src == Source.BANK && bankQuestionId == null) {
            throw new IllegalArgumentException("bankQuestionId required for BANK source");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        ProbeSession session = new ProbeSession(
                id,
                studentId,
                courseId,
                nodeCode.trim(),
                nodeId,
                stem.trim(),
                type == null ? 1 : type,
                options,
                correctKey,
                src,
                bankQuestionId,
                Instant.now(),
                false,
                null);
        sessions.put(id, session);
        return session;
    }

    public Optional<ProbeSession> find(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(sessionId.trim()));
    }

    /**
     * Mark submitted with correctness. Idempotent: if already submitted, returns prior state.
     *
     * @return empty if session missing or ownership mismatch
     */
    public Optional<ProbeSession> submit(String sessionId,
                                         Long studentId,
                                         Long courseId,
                                         boolean correct) {
        if (!StringUtils.hasText(sessionId) || studentId == null || courseId == null) {
            return Optional.empty();
        }
        String id = sessionId.trim();
        ProbeSession existing = sessions.get(id);
        if (existing == null) {
            return Optional.empty();
        }
        if (!Objects.equals(existing.studentId(), studentId)
                || !Objects.equals(existing.courseId(), courseId)) {
            return Optional.empty();
        }
        if (existing.submitted()) {
            return Optional.of(existing);
        }
        ProbeSession updated = new ProbeSession(
                existing.sessionId(),
                existing.studentId(),
                existing.courseId(),
                existing.nodeCode(),
                existing.nodeId(),
                existing.stem(),
                existing.type(),
                existing.options(),
                existing.correctKey(),
                existing.source(),
                existing.bankQuestionId(),
                existing.createdAt(),
                true,
                correct);
        sessions.put(id, updated);
        return Optional.of(updated);
    }

    /** Bank question ids already used in open or submitted probes for this student×course×node. */
    public java.util.Set<Long> usedBankQuestionIds(Long studentId, Long courseId, String nodeCode) {
        java.util.Set<Long> used = new java.util.HashSet<>();
        if (studentId == null || courseId == null || !StringUtils.hasText(nodeCode)) {
            return used;
        }
        String code = nodeCode.trim();
        for (ProbeSession s : sessions.values()) {
            if (Objects.equals(s.studentId(), studentId)
                    && Objects.equals(s.courseId(), courseId)
                    && code.equals(s.nodeCode())
                    && s.bankQuestionId() != null) {
                used.add(s.bankQuestionId());
            }
        }
        return used;
    }

    public void clear() {
        sessions.clear();
    }

    public int size() {
        return sessions.size();
    }

    /** Snapshot for tests. */
    Map<String, ProbeSession> snapshot() {
        return Map.copyOf(sessions);
    }
}
