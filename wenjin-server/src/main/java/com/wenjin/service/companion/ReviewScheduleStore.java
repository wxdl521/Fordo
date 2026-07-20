package com.wenjin.service.companion;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory pin store for companion {@code schedule_review}.
 *
 * <p>Keys are {@code (studentId, courseId, LocalDate, nodeCode)} with a schedule mode.
 * Same-day same-node pins are idempotent (last write wins on mode).
 *
 * <p><b>Persistence deferred (M5):</b> this store is process-local and not backed by a
 * database table. Pins are lost on restart and are not shared across instances. A durable
 * store can replace this component later without changing tool contracts.
 */
@Component
public class ReviewScheduleStore {

    public enum Mode {
        ADVANCE,
        TODAY
    }

    private final ConcurrentHashMap<PinKey, Mode> pins = new ConcurrentHashMap<>();

    /**
     * Pin {@code nodeCode} onto today's review queue for the student/course.
     *
     * @return true if this is a new pin for the day; false if already pinned (mode may update)
     */
    public boolean pin(Long studentId, Long courseId, String nodeCode, Mode mode) {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(mode, "mode");
        if (!StringUtils.hasText(nodeCode)) {
            throw new IllegalArgumentException("nodeCode is required");
        }
        PinKey key = new PinKey(studentId, courseId, LocalDate.now(), nodeCode.trim());
        Mode previous = pins.put(key, mode);
        return previous == null;
    }

    /**
     * Today's forced pins for the student/course, insertion-order stable by key natural order
     * of first appearance in the map iteration.
     *
     * @return map of nodeCode → mode (never null)
     */
    public Map<String, Mode> forcedToday(Long studentId, Long courseId) {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(courseId, "courseId");
        LocalDate today = LocalDate.now();
        Map<String, Mode> result = new LinkedHashMap<>();
        for (Map.Entry<PinKey, Mode> entry : pins.entrySet()) {
            PinKey key = entry.getKey();
            if (Objects.equals(key.studentId(), studentId)
                    && Objects.equals(key.courseId(), courseId)
                    && Objects.equals(key.date(), today)) {
                result.put(key.nodeCode(), entry.getValue());
            }
        }
        return result;
    }

    /** Clear all pins (eval reset / maintenance). */
    public void clear() {
        pins.clear();
    }

    /** Clear pins for one student×course (all dates). */
    public void clearStudent(Long studentId, Long courseId) {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(courseId, "courseId");
        pins.keySet().removeIf(key ->
                Objects.equals(key.studentId(), studentId) && Objects.equals(key.courseId(), courseId));
    }

    private record PinKey(Long studentId, Long courseId, LocalDate date, String nodeCode) {
    }
}
