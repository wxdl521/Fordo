package com.wenjin.service.companion;

import com.wenjin.dto.CodeResultVO;
import com.wenjin.dto.InsightCard;
import com.wenjin.dto.PracticeStartVO;

/**
 * @param guarded true when ToolGovernor intercepted before/without successful tool body
 *                completion (budget / whitelist / invalid args / timeout). Callers should
 *                record {@code GUARD} steps for guarded results and {@code TOOL_CALL} otherwise.
 * @param cached  true when the result was served from {@code TraceContext.toolCache}
 *                (status should be recorded as {@code CACHED}; budget not consumed).
 * @param codeCard  card for the SSE {@code code_result} event (run_code_check); never carries source
 * @param insightCard table card for the SSE {@code insight_card} event (teacher assistant tools)
 */
public record ToolExecutionResult(String toolCallId,
                                  String toolName,
                                  boolean ok,
                                  String content,
                                  PracticeStartVO practiceCard,
                                  boolean guarded,
                                  boolean cached,
                                  CodeResultVO codeCard,
                                  InsightCard insightCard) {

    public ToolExecutionResult(String toolCallId, String toolName, boolean ok, String content,
                               PracticeStartVO practiceCard, boolean guarded, boolean cached,
                               CodeResultVO codeCard) {
        this(toolCallId, toolName, ok, content, practiceCard, guarded, cached, codeCard, null);
    }

    public ToolExecutionResult(String toolCallId, String toolName, boolean ok, String content,
                               PracticeStartVO practiceCard, boolean guarded, boolean cached) {
        this(toolCallId, toolName, ok, content, practiceCard, guarded, cached, null, null);
    }

    public ToolExecutionResult(String toolCallId, String toolName, boolean ok, String content) {
        this(toolCallId, toolName, ok, content, null, false, false);
    }

    public ToolExecutionResult(String toolCallId, String toolName, boolean ok, String content,
                               PracticeStartVO practiceCard) {
        this(toolCallId, toolName, ok, content, practiceCard, false, false);
    }

    public ToolExecutionResult(String toolCallId, String toolName, boolean ok, String content,
                               PracticeStartVO practiceCard, boolean guarded) {
        this(toolCallId, toolName, ok, content, practiceCard, guarded, false);
    }

    public ToolExecutionResult {
        if (!ok) {
            practiceCard = null;
            codeCard = null;
            insightCard = null;
        }
        if (cached) {
            // Cached hits are successful short-circuits, never governor intercepts.
            guarded = false;
            ok = true;
        }
    }
}
