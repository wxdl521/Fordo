package com.wenjin.eval.fixture;

import java.util.Objects;
import java.util.Set;

/**
 * Shared eval isolation world: one teacher, one student, one course, known node codes.
 */
public record EvalWorld(
        Long teacherId,
        Long studentId,
        Long courseId,
        String courseCode,
        Set<String> nodeCodes
) {
    public EvalWorld {
        Objects.requireNonNull(teacherId, "teacherId");
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(courseCode, "courseCode");
        nodeCodes = nodeCodes == null ? Set.of() : Set.copyOf(nodeCodes);
    }
}
