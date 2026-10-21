package com.wenjin.ai;

/**
 * Thrown when the downstream consumer (e.g. SSE sink) of a streamed AI turn fails,
 * typically because the client disconnected. Distinct from LLM/provider failures
 * (BusinessException AI_ERROR) so orchestration can avoid degrading on it.
 */
public class SinkFailureException extends RuntimeException {

    public SinkFailureException(Throwable cause) {
        super(cause == null ? null : cause.getMessage(), cause);
    }

    public SinkFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
