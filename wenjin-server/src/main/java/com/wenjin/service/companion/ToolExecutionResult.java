package com.wenjin.service.companion;

import com.wenjin.dto.PracticeStartVO;

public record ToolExecutionResult(String toolCallId,
                                  String toolName,
                                  boolean ok,
                                  String content,
                                  PracticeStartVO practiceCard) {

    public ToolExecutionResult(String toolCallId, String toolName, boolean ok, String content) {
        this(toolCallId, toolName, ok, content, null);
    }

    public ToolExecutionResult {
        if (!ok) {
            practiceCard = null;
        }
    }
}
