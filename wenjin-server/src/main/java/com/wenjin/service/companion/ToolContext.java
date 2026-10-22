package com.wenjin.service.companion;

/**
 * Runtime context for companion / teacher-assistant tool execution.
 *
 * @param studentId               authenticated student (null in a teacher session)
 * @param courseId                active course (teacher session: already verified via assertManageCourse
 *                                on the request thread — tools must NOT re-authorize, CurrentUser is not
 *                                available on tool threads)
 * @param focusNodeCode           conversation focus node code, if any (nullable)
 * @param reviewPracticeSessionId practice session the student asked to review this turn (nullable;
 *                                ownership already verified by the controller; valid for one turn only)
 * @param actorUserId             authenticated user driving the session (student id or teacher id)
 * @param audience                which tool audience this session may call
 */
public record ToolContext(Long studentId,
                          Long courseId,
                          String focusNodeCode,
                          Long reviewPracticeSessionId,
                          Long actorUserId,
                          ToolAudience audience) {

    public ToolContext {
        if (audience == null) {
            audience = ToolAudience.STUDENT;
        }
        if (actorUserId == null && audience == ToolAudience.STUDENT) {
            actorUserId = studentId;
        }
    }

    /** Student context (backward compatible). */
    public ToolContext(Long studentId, Long courseId, String focusNodeCode, Long reviewPracticeSessionId) {
        this(studentId, courseId, focusNodeCode, reviewPracticeSessionId, studentId, ToolAudience.STUDENT);
    }

    /** Backward-compatible constructor without a review session. */
    public ToolContext(Long studentId, Long courseId, String focusNodeCode) {
        this(studentId, courseId, focusNodeCode, null);
    }

    /** Backward-compatible constructor when focus is unknown. */
    public ToolContext(Long studentId, Long courseId) {
        this(studentId, courseId, null, null);
    }

    /** Teacher-assistant context; {@code courseId} must already be authorized for {@code teacherId}. */
    public static ToolContext forTeacher(long teacherId, long courseId) {
        return new ToolContext(null, courseId, null, null, teacherId, ToolAudience.TEACHER);
    }

    public boolean isTeacher() {
        return audience == ToolAudience.TEACHER;
    }
}
