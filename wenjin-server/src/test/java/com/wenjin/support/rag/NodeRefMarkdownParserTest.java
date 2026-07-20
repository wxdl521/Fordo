package com.wenjin.support.rag;

import com.wenjin.support.rag.NodeRefMarkdownParser.NodeRefParseException;
import com.wenjin.support.rag.NodeRefMarkdownParser.ParsedNodeRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class NodeRefMarkdownParserTest {

    @Test
    @DisplayName("样例 SE-3.2 解析出 frontmatter 与三个 ## 节")
    void sampleSe32_ok() throws Exception {
        String md = read("rag/node-ref/SE-3.2.md");
        ParsedNodeRef doc = NodeRefMarkdownParser.parse(md, Set.of("SE-3.2", "SE-1.1"));
        assertThat(doc.nodeCode()).isEqualTo("SE-3.2");
        assertThat(doc.title()).isEqualTo("用例图");
        assertThat(doc.sourceRef()).contains("软件工程导论");
        assertThat(doc.sections()).hasSize(3);
        assertThat(doc.sections().get(0).heading()).isEqualTo("概念");
        assertThat(doc.sections().get(0).content()).contains("用例图");
        assertThat(doc.sections().get(1).heading()).isEqualTo("关键要点");
        assertThat(doc.sections().get(2).heading()).isEqualTo("常见误区");
    }

    @Test
    @DisplayName("三篇样例均可解析且 node_code 与文件名一致")
    void allSamples_ok() throws Exception {
        for (String code : List.of("SE-1.1", "SE-3.1", "SE-3.2")) {
            ParsedNodeRef doc = NodeRefMarkdownParser.parse(
                    read("rag/node-ref/" + code + ".md"), Set.of(code));
            assertThat(doc.nodeCode()).isEqualTo(code);
            assertThat(doc.sections()).isNotEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCases")
    void invalidDocuments_failWithDetails(String name, String markdown, String expectedDetailFragment) {
        assertThatThrownBy(() -> NodeRefMarkdownParser.parse(markdown, Set.of("SE-3.2")))
                .isInstanceOf(NodeRefParseException.class)
                .satisfies(ex -> {
                    NodeRefParseException npe = (NodeRefParseException) ex;
                    assertThat(npe.details()).anyMatch(d -> d.contains(expectedDetailFragment));
                });
    }

    static Stream<Arguments> invalidCases() {
        return Stream.of(
                arguments("empty", "", "empty"),
                arguments("no_frontmatter", "## 概念\n正文", "frontmatter"),
                arguments("missing_node_code", """
                        ---
                        title: t
                        source_ref: s
                        ---
                        ## 概念
                        body
                        """, "node_code"),
                arguments("missing_title", """
                        ---
                        node_code: SE-3.2
                        source_ref: s
                        ---
                        ## 概念
                        body
                        """, "title"),
                arguments("missing_source_ref", """
                        ---
                        node_code: SE-3.2
                        title: t
                        ---
                        ## 概念
                        body
                        """, "source_ref"),
                arguments("bad_node_format", """
                        ---
                        node_code: 3.2-bad
                        title: t
                        source_ref: s
                        ---
                        ## 概念
                        body
                        """, "invalid format"),
                arguments("not_in_whitelist", """
                        ---
                        node_code: SE-9.9
                        title: t
                        source_ref: s
                        ---
                        ## 概念
                        body
                        """, "whitelist"),
                arguments("no_sections", """
                        ---
                        node_code: SE-3.2
                        title: t
                        source_ref: s
                        ---
                        only prose without heading
                        """, "##"),
                arguments("leading_prose", """
                        ---
                        node_code: SE-3.2
                        title: t
                        source_ref: s
                        ---
                        orphan text
                        ## 概念
                        body
                        """, "before first"),
                arguments("empty_section", """
                        ---
                        node_code: SE-3.2
                        title: t
                        source_ref: s
                        ---
                        ## 概念

                        ## 要点
                        content
                        """, "empty")
        );
    }

    private static String read(String classpath) throws Exception {
        return new ClassPathResource(classpath).getContentAsString(StandardCharsets.UTF_8);
    }
}
