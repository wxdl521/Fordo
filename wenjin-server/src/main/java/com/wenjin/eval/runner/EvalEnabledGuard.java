package com.wenjin.eval.runner;

import com.wenjin.config.FeatureFlagsProperties;

/**
 * Feature-flag gate for the eval suite ({@code wenjin.features.eval-enabled}).
 * <p>When false, runners/suites should skip rather than fail.
 */
public final class EvalEnabledGuard {

    private EvalEnabledGuard() {}

    public static boolean isEnabled(FeatureFlagsProperties flags) {
        return flags != null && flags.isEvalEnabled();
    }

    public static boolean isEnabled(Boolean evalEnabledProperty) {
        return Boolean.TRUE.equals(evalEnabledProperty);
    }

    public static final String SKIP_REASON = "wenjin.features.eval-enabled=false";
}
