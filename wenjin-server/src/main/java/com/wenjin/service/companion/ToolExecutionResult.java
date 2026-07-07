package com.wenjin.service.companion;

public record ToolExecutionResult(String toolCallId, String toolName, boolean ok, String content) {
}
