package com.wenjin.eval.fixture;

import java.util.Map;
import java.util.Set;

/**
 * Review-queue pin port for eval persona injection (backed by {@code ReviewScheduleStore} in prod).
 */
public interface EvalReviewQueuePort {

    /** Pin node codes onto today's review queue for student×course. */
    void pinAll(long studentId, long courseId, Iterable<String> nodeCodes);

    /** Clear pins for student×course. */
    void clearStudent(long studentId, long courseId);

    /** Today's forced pins (nodeCode → mode name). */
    Map<String, String> forcedToday(long studentId, long courseId);

    default Set<String> forcedTodayCodes(long studentId, long courseId) {
        return forcedToday(studentId, courseId).keySet();
    }
}
