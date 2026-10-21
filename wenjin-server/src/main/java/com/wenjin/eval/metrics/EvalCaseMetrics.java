package com.wenjin.eval.metrics;

import com.wenjin.eval.hard.EvalStepView;
import com.wenjin.eval.judge.JudgeResult;

import java.util.List;

/**
 * Per-case measurements of one scenario execution.
 *
 * @param ttftMs          time to first token (ms), null when unknown (e.g. REPLAY)
 * @param totalMs         total turn duration (ms), null when unknown
 * @param llmCalls        number of LLM rounds
 * @param toolCalls       number of tool executions
 * @param tokensIn        prompt tokens (real usage or estimate)
 * @param tokensOut       completion tokens
 * @param tokensEstimated true when tokens are estimates rather than provider usage
 * @param judge           judge result, nullable
 */
public record EvalCaseMetrics(
        Long ttftMs,
        Long totalMs,
        int llmCalls,
        int toolCalls,
        long tokensIn,
        long tokensOut,
        boolean tokensEstimated,
        JudgeResult judge
) {

    /** Derive counts and token sums from collected steps. */
    public static EvalCaseMetrics fromSteps(List<EvalStepView> steps, Long ttftMs, Long totalMs) {
        int llm = 0;
        int tools = 0;
        long in = 0;
        long out = 0;
        boolean missingTokens = false;
        if (steps != null) {
            for (EvalStepView s : steps) {
                if (s.isLlmCall()) {
                    llm++;
                    if (s.tokensIn() == null && s.tokensOut() == null) {
                        missingTokens = true;
                    }
                    in += s.tokensIn() == null ? 0 : s.tokensIn();
                    out += s.tokensOut() == null ? 0 : s.tokensOut();
                } else if (s.isToolCall()) {
                    tools++;
                }
            }
        }
        return new EvalCaseMetrics(ttftMs, totalMs, llm, tools, in, out, missingTokens && llm > 0, null);
    }

    public EvalCaseMetrics withJudge(JudgeResult j) {
        return new EvalCaseMetrics(ttftMs, totalMs, llmCalls, toolCalls, tokensIn, tokensOut, tokensEstimated, j);
    }
}
