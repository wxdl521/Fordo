package com.wenjin.support;

/**
 * Phase-1 decision intent (M9). Missing / unknown values default to {@link #CHAT}
 * so older goldens without the field stay compatible.
 */
public enum CompanionIntent {
    EXPLAIN,
    QUERY,
    PRACTICE,
    CHAT;

    /**
     * Parse a raw intent string; null/blank/unknown → {@link #CHAT}.
     */
    public static CompanionIntent fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return CHAT;
        }
        String upper = raw.trim().toUpperCase();
        return switch (upper) {
            case "EXPLAIN" -> EXPLAIN;
            case "QUERY" -> QUERY;
            case "PRACTICE" -> PRACTICE;
            case "CHAT" -> CHAT;
            default -> CHAT;
        };
    }
}
