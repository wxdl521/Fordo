package com.wenjin.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class StreamingToolProtocolFilterTest {

    private static String runChunks(List<String> chunks) {
        StreamingToolProtocolFilter f = new StreamingToolProtocolFilter();
        StringBuilder sb = new StringBuilder();
        for (String c : chunks) {
            sb.append(f.accept(c));
        }
        sb.append(f.flush());
        return sb.toString();
    }

    private static String runWhole(String s) {
        return runChunks(List.of(s));
    }

    private static String runPerChar(String s) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            chunks.add(s.substring(i, i + 1));
        }
        return runChunks(chunks);
    }

    private static String runSplitAt(String s, int pos) {
        return runChunks(List.of(s.substring(0, pos), s.substring(pos)));
    }

    static Stream<Arguments> protocolCases() {
        return Stream.of(
                Arguments.of("dsml ascii",
                        "前文<|DSML|tool_calls><|DSML|invoke name=\"x\"><|DSML|parameter name=\"a\">1</|DSML|parameter></|DSML|invoke></|DSML|tool_calls>后文",
                        "前文后文"),
                Arguments.of("dsml fullwidth",
                        "前文<｜DSML｜tool_calls><｜DSML｜invoke name=\"x\"><｜DSML｜parameter name=\"a\">1</｜DSML｜parameter></｜DSML｜invoke></｜DSML｜tool_calls>后文",
                        "前文后文"),
                Arguments.of("plain tool_calls",
                        "a <tool_calls><invoke name=\"q\">v</invoke></tool_calls> tail",
                        "a  tail"),
                Arguments.of("uppercase", "x<TOOL_CALLS>junk</TOOL_CALLS>ok", "xok"),
                Arguments.of("bare invoke", "a<invoke name=\"q\">v</invoke>b", "ab"),
                Arguments.of("bare parameter", "a<parameter name=\"q\">v</parameter>b", "ab"),
                Arguments.of("single special tag", "a<|im_end|>b", "ab"),
                Arguments.of("fullwidth special tag", "a<｜end▁of▁sentence｜>b", "ab"),
                Arguments.of("unclosed block", "hi<tool_calls><invoke name=\"x\">", "hi"),
                Arguments.of("unclosed dsml with text inside", "你好<｜DSML｜tool_calls>\nfoo bar", "你好"),
                Arguments.of("unfinished tag at end", "hi<invoke name=\"x", "hi"),
                Arguments.of("two blocks", "a<tool_calls>1</tool_calls>b<tool_calls>2</tool_calls>c", "abc"),
                Arguments.of("deepseek native tool calls",
                        "前<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>query_mastery\n```json\n{\"nodeCode\":\"SE-1\"}\n```<｜tool▁call▁end｜><｜tool▁calls▁end｜>后",
                        "前后"),
                Arguments.of("deepseek native single call",
                        "前<｜tool▁call▁begin｜>function<｜tool▁sep｜>get_x\n{}<｜tool▁call▁end｜>后",
                        "前后"),
                Arguments.of("deepseek native unclosed", "前<｜tool▁calls▁begin｜>function<｜tool▁sep｜>x", "前"),
                Arguments.of("whitespace preserved", "  a \n\n<tool_calls>x</tool_calls>\n b  ", "  a \n\n\n b  "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protocolCases")
    void protocolBlocksDroppedAtEveryBoundary(String name, String input, String expected) {
        assertEquals(expected, runWhole(input), "whole");
        assertEquals(expected, runPerChar(input), "per char");
        for (int i = 0; i <= input.length(); i++) {
            assertEquals(expected, runSplitAt(input, i), "split at " + i);
        }
    }

    static Stream<String> falsePositives() {
        return Stream.of(
                "List<String> xs = new ArrayList<>();",
                "if a<b then",
                "x <= y and 1 < 2",
                "<br>换行<br/>",
                "<p>hi</p>",
                "<tooltip>t</tooltip><toolbar>",
                "I <3 you",
                "A｜B 中文｜竖线正常使用",
                "a | b || c",
                "Map<K, V> m; <parameters> <invokes>",
                "tool_calls 这个词本身不带尖括号 DSML",
                "a <| b",
                "x <｜ 中文竖线 y",
                "管道 <| 后面是一段很长很长的普通文字，没有任何闭合尖括号，也不是协议 end");
    }

    @ParameterizedTest
    @MethodSource("falsePositives")
    void ordinaryTextPassesThroughUnchanged(String input) {
        assertEquals(input, runWhole(input));
        assertEquals(input, runPerChar(input));
        for (int i = 0; i <= input.length(); i++) {
            assertEquals(input, runSplitAt(input, i), "split at " + i);
        }
    }

    @Test
    void undecidedPrefixAtEndOfStreamIsReleasedOnFlush() {
        StreamingToolProtocolFilter f = new StreamingToolProtocolFilter();
        assertEquals("x", f.accept("x<tool"));
        assertEquals("<tool", f.flush());
    }

    @Test
    void nonPrefixReleasedImmediately() {
        StreamingToolProtocolFilter f = new StreamingToolProtocolFilter();
        assertEquals("a<b", f.accept("a<b"));
        assertEquals("List", f.accept("List<"));
        assertEquals("<String>", f.accept("String>"));
        assertEquals("<br>", f.accept("<br>"));
        assertEquals("", f.flush());
    }

    @Test
    void holdBackIsCapped() {
        StreamingToolProtocolFilter f = new StreamingToolProtocolFilter();
        String s = "<" + " ".repeat(40);
        assertEquals(s, f.accept(s));
    }

    @Test
    void textAfterBlockIsEmittedWhileStreaming() {
        StreamingToolProtocolFilter f = new StreamingToolProtocolFilter();
        assertEquals("a", f.accept("a<tool_calls>xx"));
        assertEquals("", f.accept("yy</tool_"));
        assertEquals("b", f.accept("calls>b"));
        assertEquals("", f.flush());
    }

    @Test
    void protocolFreeInputRoundTripsUnderRandomChunking() {
        String[] atoms = {"hello", " ", "\n", "<", ">", "<=", "a<b", "List<String>", "<br>", "中文", "｜", "|",
                "<t", "<to", "<i", "<p", "tool", "invoke", "dsml", "</", "/>", "  ", "<tooltip>", "<3", "x"};
        Random rnd = new Random(42);
        for (int iter = 0; iter < 500; iter++) {
            StringBuilder in = new StringBuilder();
            int n = 1 + rnd.nextInt(30);
            for (int i = 0; i < n; i++) {
                in.append(atoms[rnd.nextInt(atoms.length)]);
            }
            String input = in.toString();
            // 随机拼接可能偶然拼出协议前缀（如 "<t"+"ool"+... 不会完整成 tool_calls，但 "<" "i"+"nvoke" 后接空白可能）；
            // 这里只断言不含完整协议起始时的恒等。
            if (input.toLowerCase().matches("(?s).*<\\s*/?\\s*(tool_calls|invoke|parameter|dsml)[\\s>/|｜].*")
                    || input.matches("(?s).*</?[|｜].*")) {
                continue;
            }
            List<String> chunks = new ArrayList<>();
            int pos = 0;
            while (pos < input.length()) {
                int len = 1 + rnd.nextInt(5);
                int end = Math.min(input.length(), pos + len);
                chunks.add(input.substring(pos, end));
                pos = end;
            }
            assertEquals(input, runChunks(chunks), "input=" + input);
        }
    }

    @Test
    void pipeStartWithoutCloseIsReleasedOnceHoldCapExceeded() {
        StreamingToolProtocolFilter f = new StreamingToolProtocolFilter();
        String head = f.accept("a <| b");
        String more = f.accept(" 这是一段足够长的普通文字，超过暂扣上限也没有闭合尖括号的情况");
        // 不必等到 flush：超过 MAX_HOLD 即放行
        assertEquals("a <| b 这是一段足够长的普通文字，超过暂扣上限也没有闭合尖括号的情况", head + more);
        assertEquals("", f.flush());
    }
}
