package com.wenjin.eval.judge;

import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension;

import java.util.List;
import java.util.Objects;

/**
 * Inputs for the soft judge (LLM-as-judge).
 */
public record JudgeRequest(
        String transcript,
        String toolSummary,
        String personaNote,
        List<JudgeDimension> dimensions
) {
    public JudgeRequest {
        transcript = transcript == null ? "" : transcript;
        toolSummary = toolSummary == null ? "" : toolSummary;
        personaNote = personaNote == null ? "" : personaNote;
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        Objects.requireNonNull(dimensions);
    }
}
