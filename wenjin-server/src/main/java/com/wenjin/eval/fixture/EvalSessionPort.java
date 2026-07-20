package com.wenjin.eval.fixture;

/**
 * Clears student mutable session state between scenarios (conversations, practice, agent runs).
 */
public interface EvalSessionPort {

    void clearSessions(long studentId, long courseId);
}
