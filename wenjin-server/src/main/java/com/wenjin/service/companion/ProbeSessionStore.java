package com.wenjin.service.companion;

import com.wenjin.config.LimitsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Store for companion {@code probe_understanding} sessions (M9), persisted in {@code probe_session} (V26)
 * so a student who reloads after a restart / deploy can still submit.
 *
 * <p>Probe sessions are deliberately <b>not</b> persisted in {@code practice_session}
 * (avoids V14 conflict with M10 plan tables and keeps EWMA isolation simple): this store never touches
 * {@code student_mastery} / {@code answer_record}.
 * Ownership is enforced on submit via studentId/courseId match; submit is idempotent (first grade wins).
 * Sessions older than the TTL ({@code wenjin.limits.reflection.probe-session-ttl-hours}) read as not found;
 * old rows are purged by {@link ProbeStateCleanup}.
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

    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final ProbeSessionRepository repository;
    private final Clock clock;
    private final Supplier<Duration> ttl;

    /** Spring: DB-backed; TTL read live from {@code wenjin.limits.reflection}. */
    @Autowired
    public ProbeSessionStore(ProbeSessionRepository repository, Clock clock, LimitsProperties limits) {
        this(repository, clock, () -> Duration.ofHours(Math.max(1, limits.getReflection().getProbeSessionTtlHours())));
    }

    public ProbeSessionStore(ProbeSessionRepository repository, Clock clock, Supplier<Duration> ttl) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    /** Memory-only (unit tests without DB). */
    public ProbeSessionStore() {
        this(new InMemoryProbeSessionRepository(), Clock.systemUTC(), () -> DEFAULT_TTL);
    }

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
                clock.instant(),
                false,
                null);
        repository.insert(session);
        return session;
    }

    public Optional<ProbeSession> find(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return Optional.empty();
        }
        return repository.findById(sessionId.trim()).filter(this::alive);
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
        Optional<ProbeSession> found = find(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ProbeSession existing = found.get();
        if (!Objects.equals(existing.studentId(), studentId)
                || !Objects.equals(existing.courseId(), courseId)) {
            return Optional.empty();
        }
        if (existing.submitted()) {
            return Optional.of(existing);
        }
        // compare-and-set: concurrent / repeated submit never re-grades; loser reads the stored result
        repository.markSubmitted(id, clock.instant(), correct);
        return repository.findById(id);
    }

    private boolean alive(ProbeSession s) {
        return s.createdAt() != null && s.createdAt().plus(ttl.get()).isAfter(clock.instant());
    }

    /** Bank question ids already used in open or submitted probes for this student×course×node. */
    public java.util.Set<Long> usedBankQuestionIds(Long studentId, Long courseId, String nodeCode) {
        if (studentId == null || courseId == null || !StringUtils.hasText(nodeCode)) {
            return new java.util.HashSet<>();
        }
        return new java.util.HashSet<>(repository.usedBankQuestionIds(studentId, courseId, nodeCode.trim()));
    }

    /** Purge sessions created before {@code cutoff}; returns rows removed. */
    public int purgeCreatedBefore(Instant cutoff) {
        return repository.deleteCreatedBefore(cutoff);
    }

    public void clear() {
        repository.deleteAll();
    }

    public int size() {
        return (int) repository.count();
    }
}
