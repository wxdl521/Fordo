package com.wenjin.support.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlainDocumentSplitterTest {

    @Test
    @DisplayName("小节过小则合并，保留首节路径")
    void smallSectionsMerge() {
        String text = "前言段落。\n# 第一部分\n## 概念\n概念正文。\n## 方法\n方法正文。\n";
        List<PlainDocumentSplitter.Piece> pieces = PlainDocumentSplitter.split(text, "教材");
        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0).path()).isEqualTo("教材");
        assertThat(pieces.get(0).content()).contains("前言段落").contains("概念正文").contains("方法正文");
    }

    @Test
    @DisplayName("大节保持各自标题路径且不超 MAX")
    void largeSectionsKeepPath() {
        String big = "这是一句比较长的内容。".repeat(60);
        String text = "# 第一章\n## 进程\n" + big + "\n## 线程\n" + big;
        List<PlainDocumentSplitter.Piece> pieces = PlainDocumentSplitter.split(text, "T");
        assertThat(pieces).hasSizeGreaterThanOrEqualTo(2);
        assertThat(pieces.get(0).path()).isEqualTo("T>第一章>进程");
        assertThat(pieces.get(pieces.size() - 1).path()).isEqualTo("T>第一章>线程");
        assertThat(pieces).allMatch(p -> p.content().length() <= TextChunker.MAX);
    }

    @Test
    @DisplayName("PDF/docx 常见标题：第N章、一、、1.2 编号；句末带标点的不是标题")
    void headingHeuristics() {
        assertThat(PlainDocumentSplitter.headingLevel("第三章 进程管理")).isEqualTo(1);
        assertThat(PlainDocumentSplitter.headingLevel("第二节 调度")).isEqualTo(2);
        assertThat(PlainDocumentSplitter.headingLevel("一、基本概念")).isEqualTo(2);
        assertThat(PlainDocumentSplitter.headingLevel("2.1 进程状态")).isEqualTo(2);
        assertThat(PlainDocumentSplitter.headingLevel("3.2.1 就绪队列")).isEqualTo(3);
        assertThat(PlainDocumentSplitter.headingLevel("1. 这是一个以句号结束的列表项。")).isEqualTo(0);
        assertThat(PlainDocumentSplitter.headingLevel("2024 12 31")).isEqualTo(0);
        assertThat(PlainDocumentSplitter.headingLevel("普通正文行")).isEqualTo(0);
    }

    @Test
    @DisplayName("空文本返回空；路径超长被截断")
    void emptyAndClip() {
        assertThat(PlainDocumentSplitter.split("  \n ", "t")).isEmpty();
        List<PlainDocumentSplitter.Piece> p = PlainDocumentSplitter.split("正文", "标".repeat(400));
        assertThat(p.get(0).path().length()).isLessThanOrEqualTo(200);
    }
}
