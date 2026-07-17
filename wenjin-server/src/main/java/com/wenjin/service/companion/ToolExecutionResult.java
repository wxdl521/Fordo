package com.wenjin.service.companion;

import com.wenjin.dto.PracticeStartVO;

/**
 * @param guarded true when ToolGovernor intercepted before/without successful tool body
 *                completion (budget / whitelist / invalid args / timeout). Callers should
 *                record {@code GUARD} steps for guarded results and {@code TOOL_CALL} otherwise.
 */
public record ToolExecutionResult(String toolCallId,
                                  String toolName,
                                  boolean ok,
                                  String content,
                                  PracticeStartVO practiceCard,
                                  boolean guarded) {

    public ToolExecutionResult(String toolCallId, String toolName, boolean ok, String content) {
        this(toolCallId, toolName, ok, content, null, false);
    }

    public ToolExecutionResult(String toolCallId, String toolName, boolean ok, String content,
                               PracticeStartVO practiceCard) {
        this(toolCallId, toolName, ok, content, practiceCard, false);
    }

    public ToolExecutionResult {
        if (!ok) {
            practiceCard = null;
        }
    }
}
