package com.wenjin.service.trace;

/**
 * Per-run tool result cache entry (read-only tools only).
 *
 * @param payload normalized tool content returned to the model
 */
public record ToolCacheEntry(String payload) {
}
