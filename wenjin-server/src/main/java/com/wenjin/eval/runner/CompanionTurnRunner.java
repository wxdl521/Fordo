package com.wenjin.eval.runner;

import com.wenjin.eval.collect.CollectedReply;
import com.wenjin.eval.hard.EvalStepView;

import java.util.List;

/**
 * One student message through the companion path (LIVE / REPLAY / mocked).
 */
public interface CompanionTurnRunner {

    TurnResult runTurn(TurnRequest request);

    record TurnRequest(
            long studentId,
            long courseId,
            Long conversationId,
            String message
    ) {}

    /**
     * @param ttftMs  time to first streamed token of this turn, null when unknown / nothing streamed
     * @param totalMs wall time of the turn (startTurn + streamReply), null when unknown
     */
    record TurnResult(
            Long conversationId,
            Long runId,
            CollectedReply reply,
            List<EvalStepView> steps,
            Long ttftMs,
            Long totalMs
    ) {
        public TurnResult {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public TurnResult(Long conversationId, Long runId, CollectedReply reply, List<EvalStepView> steps) {
            this(conversationId, runId, reply, steps, null, null);
        }
    }
}
