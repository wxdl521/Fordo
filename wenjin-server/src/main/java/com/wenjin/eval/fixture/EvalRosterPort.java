package com.wenjin.eval.fixture;

/**
 * Seeds the teacher-assistant eval class roster: extra enrolled students with deterministic mastery, answers,
 * snapshot history and (optionally) a private companion chat, plus the reset of that state and of the
 * teacher-side conversations/trace. Roster users are slot-addressed ({@code eval_roster_<slot>}) so a reset is
 * bounded and idempotent no matter which scenario ran before.
 */
public interface EvalRosterPort {

    /** Max number of roster slots (matches the scenario schema limit). */
    int MAX_SLOTS = 5;

    /** Remove every roster user's enrolment (all courses) and student-scoped state. Users themselves are kept. */
    void clearRoster();

    /** Enrol / unenrol the primary eval student in the course (teacher scenarios run on the roster alone). */
    void setPrimaryStudentEnrolled(long studentId, long courseId, boolean enrolled);

    /** Ensure the slot's user exists with this real name; returns the user id. */
    long ensureRosterUser(int slot, String realName);

    void enroll(long studentId, long courseId);

    /** Enrol the student in a separate course (created on demand, taught by {@code teacherId}); returns its id. */
    long enrollInOtherCourse(long studentId, long teacherId);

    /** Current mastery: updated_at = now, so the effective score equals {@code raw01} (no decay). */
    void writeMastery(long studentId, long courseId, long nodeId, double raw01);

    /** A historical snapshot of the node: {@code daysAgo} days ago the score was {@code score01}. */
    void writeSnapshot(long studentId, long courseId, long nodeId, double score01, int daysAgo);

    /**
     * One answer on an APPROVED single-choice question whose primary node is {@code nodeId}.
     *
     * @return false when no such question exists
     */
    boolean recordAnswer(long studentId, long courseId, long nodeId, boolean correct, int minutesAgo);

    /** A private student-to-companion message (content the teacher must never see). */
    void recordPrivateChat(long studentId, long courseId, String text);

    /** Delete the teacher's assistant conversations/messages and TEACHER_CHAT trace for the course. */
    void clearTeacherState(long teacherId, long courseId);

    /** Port that does nothing (unit tests / fixtures without a roster). */
    EvalRosterPort NONE = new EvalRosterPort() {
        @Override
        public void clearRoster() {
        }

        @Override
        public void setPrimaryStudentEnrolled(long studentId, long courseId, boolean enrolled) {
        }

        @Override
        public long ensureRosterUser(int slot, String realName) {
            throw new UnsupportedOperationException("no roster support configured");
        }

        @Override
        public void enroll(long studentId, long courseId) {
        }

        @Override
        public long enrollInOtherCourse(long studentId, long teacherId) {
            throw new UnsupportedOperationException("no roster support configured");
        }

        @Override
        public void writeMastery(long studentId, long courseId, long nodeId, double raw01) {
        }

        @Override
        public void writeSnapshot(long studentId, long courseId, long nodeId, double score01, int daysAgo) {
        }

        @Override
        public boolean recordAnswer(long studentId, long courseId, long nodeId, boolean correct, int minutesAgo) {
            return false;
        }

        @Override
        public void recordPrivateChat(long studentId, long courseId, String text) {
        }

        @Override
        public void clearTeacherState(long teacherId, long courseId) {
        }
    };
}
