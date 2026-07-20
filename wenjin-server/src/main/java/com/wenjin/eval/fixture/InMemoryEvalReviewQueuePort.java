package com.wenjin.eval.fixture;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory review queue pins for unit tests. */
public final class InMemoryEvalReviewQueuePort implements EvalReviewQueuePort {

    private final ConcurrentHashMap<String, Map<String, String>> byStudentCourse = new ConcurrentHashMap<>();

    @Override
    public void pinAll(long studentId, long courseId, Iterable<String> nodeCodes) {
        Objects.requireNonNull(nodeCodes, "nodeCodes");
        Map<String, String> pins = byStudentCourse.computeIfAbsent(key(studentId, courseId),
                k -> new ConcurrentHashMap<>());
        for (String code : nodeCodes) {
            if (code != null && !code.isBlank()) {
                pins.put(code.trim(), "TODAY");
            }
        }
    }

    @Override
    public void clearStudent(long studentId, long courseId) {
        byStudentCourse.remove(key(studentId, courseId));
    }

    @Override
    public Map<String, String> forcedToday(long studentId, long courseId) {
        Map<String, String> pins = byStudentCourse.get(key(studentId, courseId));
        if (pins == null || pins.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(pins));
    }

    private static String key(long studentId, long courseId) {
        return studentId + ":" + courseId;
    }
}
