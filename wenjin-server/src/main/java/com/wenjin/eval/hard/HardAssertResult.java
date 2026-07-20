package com.wenjin.eval.hard;

import java.util.List;

/**
 * Aggregate hard-assert outcome.
 */
public record HardAssertResult(boolean passed, List<HardAssertFailure> failures) {
    public HardAssertResult {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public static HardAssertResult pass() {
        return new HardAssertResult(true, List.of());
    }

    public static HardAssertResult fail(List<HardAssertFailure> failures) {
        List<HardAssertFailure> list = failures == null ? List.of() : List.copyOf(failures);
        return new HardAssertResult(list.isEmpty(), list);
    }
}
