package com.wenjin.eval.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.eval.hard.EvalStepView;

import java.util.ArrayList;
import java.util.List;

/**
 * Judge evidence for {@code citation_faithfulness}: the chunks a turn's {@code search_materials} calls returned,
 * rendered one line each ({@code 资料 #id（来源）：片段}), so the judge can compare the reply's citations with
 * what the retrieval actually said instead of seeing tool names only.
 */
final class CitationEvidence {

    static final int MAX_LINES = 8;
    static final int SNIPPET_CHARS = 120;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CitationEvidence() {
    }

    static List<String> from(List<EvalStepView> steps) {
        List<String> lines = new ArrayList<>();
        if (steps == null) {
            return lines;
        }
        for (EvalStepView step : steps) {
            if (!step.isToolCall() || !"search_materials".equals(step.name()) || step.outputJson() == null) {
                continue;
            }
            try {
                for (JsonNode item : MAPPER.readTree(step.outputJson()).path("items")) {
                    if (lines.size() >= MAX_LINES) {
                        return lines;
                    }
                    if (!item.path("chunkId").isIntegralNumber()) {
                        continue;
                    }
                    String snippet = item.path("snippet").asText("");
                    if (snippet.length() > SNIPPET_CHARS) {
                        snippet = snippet.substring(0, SNIPPET_CHARS) + "…";
                    }
                    String title = item.path("sourceTitle").asText("");
                    lines.add("资料 #" + item.path("chunkId").asLong()
                            + (title.isBlank() ? "" : "（" + title + "）") + "：" + snippet);
                }
            } catch (Exception ignored) {
                // tool output that is not JSON contributes no evidence
            }
        }
        return lines;
    }
}
