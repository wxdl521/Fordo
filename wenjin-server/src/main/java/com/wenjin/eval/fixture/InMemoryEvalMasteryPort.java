package com.wenjin.eval.fixture;

import com.wenjin.service.MasteryDecayCalculator;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure in-memory mastery port for unit tests (no DB).
 * <p>Mirrors production semantics: store raw 0–100, optional backdated evidence age for effective.
 */
public final class InMemoryEvalMasteryPort implements EvalMasteryPort {

    private record Row(double raw100, double daysSinceEvidence, boolean forceDecayEligible) {}

    private final Map<String, Row> rows = new ConcurrentHashMap<>();
    private final double floor;
    private final double tauDays;
    private final boolean decayEnabled;

    public InMemoryEvalMasteryPort() {
        this(25.0, 30.0, true);
    }

    public InMemoryEvalMasteryPort(double floor, double tauDays, boolean decayEnabled) {
        this.floor = floor;
        this.tauDays = tauDays;
        this.decayEnabled = decayEnabled;
    }

    @Override
    public void writeMastery(long studentId, long courseId, long nodeId, double raw01, Double effective01) {
        double raw100 = clamp01(raw01) * 100.0;
        double days = 0.0;
        // Production decay skips mastery_level=0 (未学). When an effective target below raw is
        // requested, treat the row as at least "weak" so read-time decay can apply.
        boolean forceDecayEligible = false;
        if (effective01 != null) {
            double eff100 = clamp01(effective01) * 100.0;
            days = daysForTargetEffective(raw100, eff100);
            forceDecayEligible = days > 0 && eff100 < raw100;
        }
        rows.put(key(studentId, courseId, nodeId), new Row(raw100, days, forceDecayEligible));
    }

    @Override
    public void clearMastery(long studentId, long courseId) {
        String prefix = studentId + ":" + courseId + ":";
        Iterator<Map.Entry<String, Row>> it = rows.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey().startsWith(prefix)) {
                it.remove();
            }
        }
    }

    @Override
    public Double readRaw01(long studentId, long courseId, long nodeId) {
        Row row = rows.get(key(studentId, courseId, nodeId));
        return row == null ? null : row.raw100() / 100.0;
    }

    @Override
    public Double readEffective01(long studentId, long courseId, long nodeId) {
        Row row = rows.get(key(studentId, courseId, nodeId));
        if (row == null) {
            return null;
        }
        if (!decayEnabled) {
            return row.raw100() / 100.0;
        }
        int level = row.forceDecayEligible() || row.raw100() >= 40.0 ? 1 : 0;
        double effective = MasteryDecayCalculator.effectiveScore(
                row.raw100(), row.daysSinceEvidence(), tauDays, floor, level);
        return effective / 100.0;
    }

    /**
     * Inverse of floor + (raw−floor)·e^(−days/τ) for days when effective &lt; raw and raw &gt; floor.
     */
    static double daysForTargetEffective(double raw100, double effective100) {
        if (effective100 >= raw100 || raw100 <= 0) {
            return 0.0;
        }
        double floor = 25.0;
        double tau = 30.0;
        if (raw100 <= floor) {
            return 0.0;
        }
        // effective cannot go below floor via decay
        double target = Math.max(effective100, floor);
        if (target >= raw100) {
            return 0.0;
        }
        double ratio = (target - floor) / (raw100 - floor);
        if (ratio <= 0) {
            // max decay: large days
            return tau * 10;
        }
        return -tau * Math.log(ratio);
    }

    private static String key(long studentId, long courseId, long nodeId) {
        return studentId + ":" + courseId + ":" + nodeId;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}
