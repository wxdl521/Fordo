package com.wenjin.eval.fixture;

/**
 * Seeds recent wrong answers for persona {@code mistakes} (misconception tutoring scenarios).
 * Answer rows are student state and are cleared by {@link EvalSessionPort#clearSessions}.
 */
public interface EvalMistakePort {

    /**
     * Record one wrong practice answer on an APPROVED single-choice question whose primary node is
     * {@code nodeId}; the chosen wrong option is tagged with {@code misconceptionNodeCode}.
     *
     * @param minutesAgo answered_at offset so that earlier persona entries are more recent
     * @return true when a question was found and the answer written
     */
    boolean recordMistake(long studentId, long courseId, long nodeId, String misconceptionNodeCode, int minutesAgo);

    /** Port that seeds nothing (unit tests / fixtures without answer storage). */
    EvalMistakePort NONE = (studentId, courseId, nodeId, misconceptionNodeCode, minutesAgo) -> false;
}
