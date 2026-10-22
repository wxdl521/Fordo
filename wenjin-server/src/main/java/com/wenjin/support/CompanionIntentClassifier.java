package com.wenjin.support;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic, LLM-free intent classification for one companion turn.
 * Priority: PRACTICE > QUERY (query tools only) > EXPLAIN > CHAT.
 */
public final class CompanionIntentClassifier {

    private static final Set<String> PRACTICE_TOOLS = Set.of("start_practice", "probe_understanding");

    private static final Set<String> QUERY_TOOLS =
            Set.of("draft_study_plan", "get_plan_progress", "query_mastery", "get_review_queue");

    private static final Set<String> EXPLAIN_TOOLS =
            Set.of("get_node_detail", "search_materials", "trace_prerequisites", "get_recent_mistakes",
                    "run_code_check");

    private static final List<String> EXPLAIN_KEYWORDS = List.of(
            "讲讲", "讲一下", "讲起", "讲解", "讲清", "解释", "什么是", "是什么",
            "为什么", "怎么理解", "区别", "原理", "举例");

    /** Asking for the answer itself is not a request to explain; it stays CHAT. */
    private static final List<String> ANSWER_SEEKING_KEYWORDS = List.of(
            "标准答案", "直接告诉我", "直接给我答案", "告诉我答案", "直接说答案");

    private CompanionIntentClassifier() {
    }

    public static CompanionIntent classify(String userMessage, Collection<String> toolsCalledThisTurn) {
        Set<String> tools = toolsCalledThisTurn == null ? Set.of()
                : toolsCalledThisTurn.stream().filter(Objects::nonNull).collect(Collectors.toSet());

        if (containsAny(tools, PRACTICE_TOOLS)) {
            return CompanionIntent.PRACTICE;
        }
        boolean explainTool = containsAny(tools, EXPLAIN_TOOLS);
        if (containsAny(tools, QUERY_TOOLS) && !explainTool) {
            return CompanionIntent.QUERY;
        }
        if (explainTool || asksForExplanation(userMessage)) {
            return CompanionIntent.EXPLAIN;
        }
        return CompanionIntent.CHAT;
    }

    private static boolean asksForExplanation(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (containsAnyKeyword(message, ANSWER_SEEKING_KEYWORDS)) {
            return false;
        }
        return containsAnyKeyword(message, EXPLAIN_KEYWORDS);
    }

    private static boolean containsAny(Set<String> tools, Set<String> candidates) {
        for (String candidate : candidates) {
            if (tools.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyKeyword(String message, List<String> keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
