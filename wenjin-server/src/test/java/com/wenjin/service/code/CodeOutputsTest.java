package com.wenjin.service.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeOutputsTest {

    @Test
    @DisplayName("比对：逐行去行尾空白、去末尾空行后精确相等")
    void outputsMatch_rule() {
        assertThat(CodeOutputs.outputsMatch("3\n", "3")).isTrue();
        assertThat(CodeOutputs.outputsMatch("1 2\n3", "1 2   \n3\t\n\n\n")).isTrue();
        assertThat(CodeOutputs.outputsMatch("a\r\nb\r\n", "a\nb")).isTrue();
        assertThat(CodeOutputs.outputsMatch("", "\n\n  \n")).isTrue();
        assertThat(CodeOutputs.outputsMatch(null, "")).isTrue();
        // 行首空白、行间空行、大小写都必须一致
        assertThat(CodeOutputs.outputsMatch("a", " a")).isFalse();
        assertThat(CodeOutputs.outputsMatch("a\nb", "a\n\nb")).isFalse();
        assertThat(CodeOutputs.outputsMatch("Yes", "yes")).isFalse();
        assertThat(CodeOutputs.outputsMatch("12", "1 2")).isFalse();
        assertThat(CodeOutputs.outputsMatch("x", null)).isFalse();
    }

    @Test
    @DisplayName("normalize 只动行尾与末尾空行")
    void normalize() {
        assertThat(CodeOutputs.normalize("  a  \n b\t\r\n\n")).isEqualTo("  a\n b");
        assertThat(CodeOutputs.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("预览截断：不超限原样；超限截到上限并标记；不劈开代理对")
    void preview_truncation() {
        CodeOutputs.Preview p = CodeOutputs.preview("hello", 10);
        assertThat(p.text()).isEqualTo("hello");
        assertThat(p.truncated()).isFalse();

        CodeOutputs.Preview q = CodeOutputs.preview("x".repeat(600), 500);
        assertThat(q.text()).hasSize(500);
        assertThat(q.truncated()).isTrue();

        String emoji = "ab😀cd"; // a b 😀 c d
        CodeOutputs.Preview r = CodeOutputs.preview(emoji, 3);
        assertThat(r.text()).isEqualTo("ab");
        assertThat(r.truncated()).isTrue();

        assertThat(CodeOutputs.preview(null, 5).text()).isEmpty();
    }

    @Test
    @DisplayName("编译输出去掉容器内路径前缀")
    void compileOutput_hidesContainerPath() {
        assertThat(CodeOutputs.cleanCompileOutput("/work/Main.java:3: error: ';' expected"))
                .isEqualTo("Main.java:3: error: ';' expected");
    }

    @Test
    @DisplayName("OOM 识别：堆 / 线程两类 OutOfMemoryError")
    void oomDetection() {
        assertThat(CodeOutputs.looksLikeOutOfMemory("Terminating due to java.lang.OutOfMemoryError: Java heap space"))
                .isTrue();
        assertThat(CodeOutputs.looksLikeOutOfMemory(
                "Exception in thread \"main\" java.lang.OutOfMemoryError: unable to create native thread")).isTrue();
        assertThat(CodeOutputs.looksLikeOutOfMemory("Exception in thread \"main\" java.lang.ArithmeticException"))
                .isFalse();
        assertThat(CodeOutputs.looksLikeOutOfMemory(null)).isFalse();
    }
}
