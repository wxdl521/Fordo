package com.wenjin.service.companion;

/**
 * Runtime context for companion tool execution.
 *
 * @param studentId     authenticated student
 * @param courseId      active course
 * @param focusNodeCode conversation focus node code, if any (nullable)
 */
public record ToolContext(Long studentId, Long courseId, String focusNodeCode) {

    /** Backward-compatible constructor when focus is unknown. */
    public ToolContext(Long studentId, Long courseId) {
        this(studentId, courseId, null);
    }
}
