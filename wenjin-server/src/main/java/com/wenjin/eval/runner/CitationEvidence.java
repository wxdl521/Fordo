package com.wenjin.eval.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.eval.hard.EvalStepView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Judge evidence for {@code citation_faithfulness}: the chunks a turn's RETRIEVAL slot injected ({@code RETRIEVE}
 * step) and its {@code search_materials} calls returned (each chunk once),
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
        Set<Long> seen = new HashSet<>();
        if (steps == null) {
            return lines;
        }
        for (EvalStepView step : steps) {
            boolean searched = step.isToolCall() && "search_materials".equals(step.name());
            if (!(searched || step.isRetrieve()) || step.outputJson() == null) {
                continue;
            }
            try {
                for (JsonNode item : MAPPER.readTree(step.outputJson()).path("items")) {
                    if (lines.size() >= MAX_LINES) {
                        return lines;
                    }
                    if (!item.path("chunkId").isIntegralNumber() || !seen.add(item.path("chunkId").asLong())) {
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
