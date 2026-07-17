package com.wenjin.eval.scenario;

/**
 * eval_scenario.status 取值（与 V11 注释对齐）。
 */
public enum EvalScenarioStatus {
    DISABLED(0),
    ENABLED(1),
    PENDING(2);

    private final int code;

    EvalScenarioStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static EvalScenarioStatus fromName(String name) {
        if (name == null || name.isBlank()) {
            return ENABLED;
        }
        return EvalScenarioStatus.valueOf(name.trim().toUpperCase());
    }

    public static EvalScenarioStatus fromCode(int code) {
        for (EvalScenarioStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown eval scenario status code: " + code);
    }
}
