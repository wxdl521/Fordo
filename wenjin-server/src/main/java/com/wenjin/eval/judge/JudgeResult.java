package com.wenjin.eval.judge;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of {@link JudgeService#judge}.
 *
 * @param success       false when parse/contract failed after retries (judge failure, not hard-pass)
 * @param promptVersion e.g. {@code judge-prompt-v1}
 * @param scores        dimension scores when success; empty on failure
 * @param failureReason non-null when !success
 * @param attempts      number of AI calls made (1..maxAttempts)
 */
public record JudgeResult(
        boolean success,
        String promptVersion,
        List<JudgeScore> scores,
        String failureReason,
        int attempts
) {
    public JudgeResult {
        Objects.requireNonNull(promptVersion, "promptVersion");
        scores = scores == null ? List.of() : List.copyOf(scores);
    }

    public static JudgeResult ok(String promptVersion, List<JudgeScore> scores, int attempts) {
        return new JudgeResult(true, promptVersion, scores, null, attempts);
    }

    public static JudgeResult failure(String promptVersion, String reason, int attempts) {
        return new JudgeResult(false, promptVersion, List.of(), reason == null ? "judge failed" : reason, attempts);
    }
}
