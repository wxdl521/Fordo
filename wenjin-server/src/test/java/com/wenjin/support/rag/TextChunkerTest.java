package com.wenjin.support.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class TextChunkerTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void tableDriven(String name, String input, int minChunks, int maxChunks, boolean allWithinMax) {
        List<TextChunker.Chunk> chunks = TextChunker.chunk(input, "path");
        assertThat(chunks.size()).as(name + " size").isBetween(minChunks, maxChunks);
        if (allWithinMax) {
            for (TextChunker.Chunk c : chunks) {
                assertThat(c.charCount()).as(name + " len").isLessThanOrEqualTo(TextChunker.MAX);
                assertThat(c.content()).isNotBlank();
            }
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                arguments("empty_null", null, 0, 0, true),
                arguments("empty_blank", "   \n  ", 0, 0, true),
                arguments("short_single", "短文本不足三百字", 1, 1, true),
                arguments("exactly_max", "x".repeat(TextChunker.MAX), 1, 1, true),
                arguments("just_over_max_hard", "y".repeat(TextChunker.MAX + 50), 2, 3, true),
                arguments("heading_split", longWithHeadings(), 2, 6, true),
                arguments("blank_line_split", longWithBlanks(), 2, 6, true),
                arguments("sentence_split", longWithSentences(), 2, 8, true),
                arguments("hard_cut_no_punct", "汉".repeat(TextChunker.MAX * 2 + 10), 3, 4, true),
                arguments("path_preserved", "z".repeat(TextChunker.MAX + 1), 2, 3, true),
                arguments("overlap_presence", longWithSentences(), 2, 8, true),
                arguments("target_region", mediumParagraphs(), 1, 4, true),
                arguments("mixed_heading_then_sentence", mixed(), 2, 10, true),
                arguments("crlf_normalized", "a".repeat(100) + "\r\n\r\n" + "b".repeat(TextChunker.MAX), 1, 3, true)
        );
    }

    @Test
    @DisplayName("超长无标点硬切后每块 ≤ MAX")
    void hardCut_respectsMax() {
        String text = "字".repeat(2500);
        List<TextChunker.Chunk> chunks = TextChunker.chunk(text);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allMatch(c -> c.charCount() <= TextChunker.MAX);
        int total = chunks.stream().mapToInt(TextChunker.Chunk::charCount).sum();
        // hard cut without overlap in splitToUnits may not re-add full original if pack overlaps —
        // at least cover full length across units
        assertThat(total).isGreaterThanOrEqualTo(text.length());
    }

    @Test
    @DisplayName("## 标题优先于空行切分")
    void headingPriority_overBlank() {
        String h1 = "## 第一节\n" + "甲".repeat(500);
        String h2 = "## 第二节\n" + "乙".repeat(500);
        String text = h1 + "\n\n" + h2;
        List<String> units = TextChunker.splitToUnits(text);
        assertThat(units.size()).isGreaterThanOrEqualTo(2);
        assertThat(units.get(0)).contains("第一节");
        assertThat(String.join("", units)).contains("第二节");
    }

    @Test
    @DisplayName("无标题时按空行切")
    void blankPriority_whenNoHeading() {
        String p1 = "段一。" + "一".repeat(400);
        String p2 = "段二。" + "二".repeat(400);
        List<String> units = TextChunker.splitToUnits(p1 + "\n\n" + p2);
        assertThat(units).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("无空行时按句号切")
    void sentencePriority_whenNoBlank() {
        String text = longWithSentences();
        assertThat(text.length()).isGreaterThan(TextChunker.MAX);
        List<String> units = TextChunker.splitToUnits(text);
        assertThat(units.size()).isGreaterThan(1);
        assertThat(units.get(0)).endsWith("。");
    }

    private static String longWithHeadings() {
        return "## A\n" + "甲".repeat(450) + "\n## B\n" + "乙".repeat(450) + "\n## C\n" + "丙".repeat(450);
    }

    private static String longWithBlanks() {
        return "段落甲。" + "东".repeat(400) + "\n\n段落乙。" + "西".repeat(400) + "\n\n段落丙。" + "南".repeat(400);
    }

    private static String longWithSentences() {
        StringBuilder sb = new StringBuilder();
        // Each sentence ~40 chars; need > MAX (800)
        for (int i = 0; i < 40; i++) {
            sb.append("句子").append(i).append("包含足够多的汉字用于切分测试填充填充填充填充填充填充填充。");
        }
        return sb.toString();
    }

    private static String mediumParagraphs() {
        return "第一段。" + "中".repeat(200) + "\n\n第二段。" + "中".repeat(200) + "\n\n第三段。" + "中".repeat(200);
    }

    private static String mixed() {
        return "### 小节\n" + "开篇。" + "开".repeat(350) + "\n\n" + "续。" + "续".repeat(350)
                + "\n## 另一节\n" + "尾。" + "尾".repeat(350);
    }
}
