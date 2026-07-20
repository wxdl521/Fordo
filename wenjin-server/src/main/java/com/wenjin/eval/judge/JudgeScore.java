package com.wenjin.eval.judge;

import java.util.Objects;

/**
 * One dimension score from the judge (1–5).
 */
public record JudgeScore(String key, int score, String reason) {
    public JudgeScore {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        reason = reason == null ? "" : reason;
    }
}
