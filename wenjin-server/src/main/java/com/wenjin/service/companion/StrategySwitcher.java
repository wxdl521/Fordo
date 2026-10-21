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

    /** 策略中文名（用于提示词与记忆）。 */
    public static String label(Strategy strategy) {
        Objects.requireNonNull(strategy, "strategy");
        return switch (strategy) {
            case ANALOGY -> "类比";
            case EXAMPLE -> "举例";
            case STEP_BY_STEP -> "分步";
            case CONTRAST -> "对比";
            case VISUAL_MAP -> "图谱";
        };
    }

    /** 策略讲法说明（注入 system prompt 动态段）。 */
    public static String instruction(Strategy strategy) {
        Objects.requireNonNull(strategy, "strategy");
        return switch (strategy) {
            case ANALOGY -> "用一个学生熟悉的生活场景做类比解释核心概念，再点明类比中每个元素对应概念的哪一部分";
            case EXAMPLE -> "先给一个具体、完整的小例子并一步步走一遍，再从例子中归纳出概念要点";
            case STEP_BY_STEP -> "把概念拆成 3～4 个小步骤，每步一句话讲清，并在每步后用一个小问题确认理解";
            case CONTRAST -> "把它与最容易混淆的相近概念并列对比，指出两者的关键差别与各自的适用场景";
            case VISUAL_MAP -> "结合知识图谱，说明它依赖哪些前置知识、又支撑哪些后续知识，用关系脉络帮助学生定位";
        };
    }

    /**
     * 探针未通过后的「换种讲法」动态提示段。
     *
     * @param nodeLabel 节点展示名（如「用例图（SE-3.2）」）
     */
    public static String reexplainDirective(Strategy strategy, String nodeLabel) {
        Objects.requireNonNull(strategy, "strategy");
        String node = nodeLabel == null || nodeLabel.isBlank() ? "该知识点" : nodeLabel.trim();
        return "## 换种讲法\n"
                + "上次讲解后，学生对「" + node + "」的理解探针未通过；本次请改用『" + label(strategy) + "』方式（"
                + instruction(strategy) + "）重新讲解，并避免重复上次的讲法。\n\n";
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
