package com.wenjin.service.companion;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure strategy switcher for re-explain after a failed probe (M9).
 *
 * <p>Excludes used and invalid strategies; returns next strategy in a deterministic order.
 */
public final class StrategySwitcher {

    /**
     * Explain / re-explain strategies. Order of {@link #ORDER} is the default try sequence.
     */
    public enum Strategy {
        ANALOGY,
        EXAMPLE,
        STEP_BY_STEP,
        CONTRAST,
        VISUAL_MAP
    }

    /** Deterministic try order. */
    public static final List<Strategy> ORDER = List.of(
            Strategy.ANALOGY,
            Strategy.EXAMPLE,
            Strategy.STEP_BY_STEP,
            Strategy.CONTRAST,
            Strategy.VISUAL_MAP);

    private StrategySwitcher() {}

    /**
     * Next unused+valid strategy, or empty if none remain.
     *
     * @param used    strategies already tried this reflection loop (may be null)
     * @param invalid strategies marked ineffective (may be null)
     */
    public static java.util.Optional<Strategy> next(Set<Strategy> used, Set<Strategy> invalid) {
        Set<Strategy> exclude = EnumSet.noneOf(Strategy.class);
        if (used != null) {
            exclude.addAll(used);
        }
        if (invalid != null) {
            exclude.addAll(invalid);
        }
        for (Strategy s : ORDER) {
            if (!exclude.contains(s)) {
                return java.util.Optional.of(s);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Remaining strategies after excluding used ∪ invalid (deterministic order).
     */
    public static List<Strategy> remaining(Set<Strategy> used, Set<Strategy> invalid) {
        Set<Strategy> exclude = EnumSet.noneOf(Strategy.class);
        if (used != null) {
            exclude.addAll(used);
        }
        if (invalid != null) {
            exclude.addAll(invalid);
        }
        List<Strategy> out = new ArrayList<>();
        for (Strategy s : ORDER) {
            if (!exclude.contains(s)) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Procedural memory write format: {@code [STRATEGY][有效/无效] ...}
     *
     * @param strategy non-null
     * @param effective true → 有效; false → 无效
     * @param detail optional free text (trimmed, may be blank)
     */
    public static String proceduralMemoryLine(Strategy strategy, boolean effective, String detail) {
        Objects.requireNonNull(strategy, "strategy");
        String tag = effective ? "有效" : "无效";
        String base = "[" + strategy.name() + "][" + tag + "]";
        if (detail == null || detail.isBlank()) {
            return base;
        }
        String d = detail.trim();
        if (d.length() > 160) {
            d = d.substring(0, 160);
        }
        return base + " " + d;
    }
}
