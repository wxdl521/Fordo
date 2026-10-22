package com.wenjin.eval.runner;

import com.wenjin.eval.collect.CollectedReply;
import com.wenjin.eval.hard.EvalStepView;

import java.util.List;

/**
 * One student message through the companion path (LIVE / REPLAY / mocked).
 */
public interface CompanionTurnRunner {

    TurnResult runTurn(TurnRequest request);

    /**
     * @param studentId the acting user: the student for {@code actor=student}, the teacher for
     *                  {@code actor=teacher} (see {@link #userId()})
     * @param actor     {@code student} (learning companion) or {@code teacher} (teacher AI assistant)
     */
    record TurnRequest(
            long studentId,
            long courseId,
            Long conversationId,
            String message,
            String actor
    ) {
        public static final String STUDENT = "student";
        public static final String TEACHER = "teacher";

        public TurnRequest {
            actor = actor == null || actor.isBlank() ? STUDENT : actor;
        }

        /** Student turn (the default actor). */
        public TurnRequest(long studentId, long courseId, Long conversationId, String message) {
            this(studentId, courseId, conversationId, message, STUDENT);
        }

        /** The acting user id (student id or teacher id depending on {@link #actor()}). */
        public long userId() {
            return studentId;
        }

        public boolean isTeacher() {
            return TEACHER.equals(actor);
        }
    }

    /**
     * @param ttftMs  time to first streamed token of this turn, null when unknown / nothing streamed
     * @param totalMs wall time of the turn (startTurn + streamReply), null when unknown
     */
    record TurnResult(
            Long conversationId,
            Long runId,
            CollectedReply reply,
            List<EvalStepView> steps,
            Long ttftMs,
            Long totalMs
    ) {
        public TurnResult {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public TurnResult(Long conversationId, Long runId, CollectedReply reply, List<EvalStepView> steps) {
            this(conversationId, runId, reply, steps, null, null);
        }
    }
}
