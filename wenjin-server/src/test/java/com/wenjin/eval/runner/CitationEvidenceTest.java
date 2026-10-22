package com.wenjin.eval.runner;

import com.wenjin.eval.hard.EvalStepView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationEvidenceTest {

    private static EvalStepView search(String out) {
        return new EvalStepView("TOOL_CALL", "search_materials", 0, "{}", out);
    }

    @Test
    void rendersOneLinePerReturnedChunk() {
        List<String> lines = CitationEvidence.from(List.of(
                new EvalStepView("TOOL_CALL", "query_mastery", 0, "{}", "{\"items\":[{\"chunkId\":1}]}"),
                search("{\"items\":[{\"chunkId\":41,\"snippet\":\"用例图描述参与者\",\"sourceTitle\":\"讲义\"},"
                        + "{\"chunkId\":42,\"snippet\":\"s\"}],\"count\":2}")));
        assertThat(lines).containsExactly("资料 #41（讲义）：用例图描述参与者", "资料 #42：s");
    }

    @Test
    void truncatesLongSnippetsAndCapsLines() {
        String longSnippet = "字".repeat(CitationEvidence.SNIPPET_CHARS + 30);
        StringBuilder items = new StringBuilder("{\"items\":[");
        for (int i = 1; i <= 12; i++) {
            items.append(i > 1 ? "," : "").append("{\"chunkId\":").append(i)
                    .append(",\"snippet\":\"").append(longSnippet).append("\"}");
        }
        items.append("]}");
        List<String> lines = CitationEvidence.from(List.of(search(items.toString())));
        assertThat(lines).hasSize(CitationEvidence.MAX_LINES);
        assertThat(lines.get(0)).endsWith("…");
    }

    @Test
    void nonJsonOrMissingOutputContributesNothing() {
        assertThat(CitationEvidence.from(List.of(search("oops"), search(null)))).isEmpty();
        assertThat(CitationEvidence.from(null)).isEmpty();
    }
}
