package com.wenjin.eval.runner;

/** Eval execution mode: LIVE (real/mock AI) or REPLAY (golden {@code llmCalls}). */
public enum EvalMode {
    REPLAY,
    LIVE
}
