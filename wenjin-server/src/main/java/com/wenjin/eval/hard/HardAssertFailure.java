package com.wenjin.eval.hard;

/**
 * One failed hard-assert check.
 *
 * @param check   stable check key (e.g. mustCallTools)
 * @param message human-readable reason
 */
public record HardAssertFailure(String check, String message) {
    public HardAssertFailure {
        if (check == null || check.isBlank()) {
            throw new IllegalArgumentException("check is required");
        }
        message = message == null ? "" : message;
    }
}
