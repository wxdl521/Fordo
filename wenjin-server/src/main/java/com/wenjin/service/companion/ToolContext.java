package com.wenjin.service.companion;

/**
 * Runtime context for companion tool execution.
 *
 * @param studentId               authenticated student
 * @param courseId                active course
 * @param focusNodeCode           conversation focus node code, if any (nullable)
 * @param reviewPracticeSessionId practice session the student asked to review this turn (nullable;
 *                                ownership already verified by the controller; valid for one turn only)
 */
public record ToolContext(Long studentId, Long courseId, String focusNodeCode, Long reviewPracticeSessionId) {

    /** Backward-compatible constructor without a review session. */
    public ToolContext(Long studentId, Long courseId, String focusNodeCode) {
        this(studentId, courseId, focusNodeCode, null);
    }

    /** Backward-compatible constructor when focus is unknown. */
    public ToolContext(Long studentId, Long courseId) {
        this(studentId, courseId, null, null);
    }
}
