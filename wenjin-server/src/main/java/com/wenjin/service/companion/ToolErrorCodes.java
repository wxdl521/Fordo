package com.wenjin.service.companion;

/**
 * Stable tool-error codes returned in the model-facing error envelope.
 * Stack traces never appear in content — only these codes + short messages.
 */
public final class ToolErrorCodes {

    public static final String TOOL_TIMEOUT = "TOOL_TIMEOUT";
    public static final String TOOL_BAD_ARGS = "TOOL_BAD_ARGS";
    public static final String TOOL_NOT_ALLOWED = "TOOL_NOT_ALLOWED";
    public static final String TOOL_BUDGET_EXCEEDED = "TOOL_BUDGET_EXCEEDED";
    public static final String TOOL_INTERNAL = "TOOL_INTERNAL";

    private ToolErrorCodes() {
    }

    /** Whether the model may reasonably retry the same call. */
    public static boolean retryable(String code) {
        if (code == null) {
            return false;
        }
        return TOOL_TIMEOUT.equals(code) || TOOL_INTERNAL.equals(code);
    }
}
