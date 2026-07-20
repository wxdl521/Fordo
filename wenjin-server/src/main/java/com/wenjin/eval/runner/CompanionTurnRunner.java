package com.wenjin.eval.runner;

import com.wenjin.eval.collect.CollectedReply;
import com.wenjin.eval.hard.EvalStepView;

import java.util.List;

/**
 * One student message through the companion path (LIVE / mocked).
 */
public interface CompanionTurnRunner {

    TurnResult runTurn(TurnRequest request);

    record TurnRequest(
            long studentId,
            long courseId,
            Long conversationId,
            String message
    ) {}

    record TurnResult(
            Long conversationId,
            Long runId,
            CollectedReply reply,
            List<EvalStepView> steps
    ) {
        public TurnResult {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }
}
