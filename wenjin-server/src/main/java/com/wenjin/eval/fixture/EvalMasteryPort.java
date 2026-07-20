package com.wenjin.eval.fixture;

/**
 * Mastery write/reset port used by {@link EvalFixture}.
 * <p>Implementations should go through evidence/mastery write paths (insertEnsure + update + snapshot),
 * not bare SQL that skips snapshot semantics.
 */
public interface EvalMasteryPort {

    /**
     * Write mastery for a node using the production-aligned path.
     *
     * @param studentId   student id
     * @param courseId    course id
     * @param nodeId      kg_node id
     * @param raw01       raw score in [0,1] (stored as 0–100)
     * @param effective01 optional effective target in [0,1]; when below raw, {@code updated_at}
     *                    is backdated so read-time decay approximates it
     */
    void writeMastery(long studentId, long courseId, long nodeId, double raw01, Double effective01);

    /** Delete student_mastery + mastery_snapshot for student×course. */
    void clearMastery(long studentId, long courseId);

    /**
     * Read raw score [0,1] if a row exists.
     */
    Double readRaw01(long studentId, long courseId, long nodeId);

    /**
     * Read effective score [0,1] via decay calculator if a row exists.
     */
    Double readEffective01(long studentId, long courseId, long nodeId);
}
