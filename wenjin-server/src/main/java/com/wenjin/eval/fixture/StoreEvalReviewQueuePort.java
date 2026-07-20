package com.wenjin.eval.fixture;

import com.wenjin.service.companion.ReviewScheduleStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Adapter over production {@link ReviewScheduleStore}. */
public final class StoreEvalReviewQueuePort implements EvalReviewQueuePort {

    private final ReviewScheduleStore store;

    public StoreEvalReviewQueuePort(ReviewScheduleStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void pinAll(long studentId, long courseId, Iterable<String> nodeCodes) {
        for (String code : nodeCodes) {
            if (code != null && !code.isBlank()) {
                store.pin(studentId, courseId, code.trim(), ReviewScheduleStore.Mode.TODAY);
            }
        }
    }

    @Override
    public void clearStudent(long studentId, long courseId) {
        store.clearStudent(studentId, courseId);
    }

    @Override
    public Map<String, String> forcedToday(long studentId, long courseId) {
        Map<String, ReviewScheduleStore.Mode> pins = store.forcedToday(studentId, courseId);
        Map<String, String> out = new LinkedHashMap<>();
        pins.forEach((k, v) -> out.put(k, v.name()));
        return Map.copyOf(out);
    }
}
